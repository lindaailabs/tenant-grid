package io.github.lindaailabs.tenantgrid.core;

import io.github.lindaailabs.tenantgrid.core.exception.TenantGridException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 租户分片元数据的数据库实现，对应 {@code tenant_shard} 表。
 *
 * <p><b>为什么生产需要它</b>：{@link InMemoryMetadataProvider} 存不住两样会变的东西——
 * 运行期新入驻的租户，和迁移期间打在租户上的 {@link TenantStatus#MIGRATING} 标记。
 * 后者尤其危险：进程重启后标记消失，路由不再认为该租户处于双写期，双写静默停止，
 * 目标库会少掉整段双写期的数据，而迁移任务本身还停在 {@code CATCH_UP}。
 *
 * <p><b>必须套上 {@link CachingMetadataProvider}</b>：{@link #find(String)} 在
 * <b>每次获取连接</b>时被调用，是最热的读路径。裸用本类，一次业务请求会对元数据库
 * 打上若干次查询：
 * <pre>{@code
 * JdbcMetadataProvider jdbc = new JdbcMetadataProvider(metaDataSource);
 * MetadataProvider metadata = new CachingMetadataProvider(jdbc, ttl, negativeTtl, maxSize);
 * }</pre>
 *
 * <p><b>必须传入独立的元数据库 {@link DataSource}，绝不能传 tenant-grid 的路由数据源</b>：
 * 路由数据源取连接时要解析 {@code TenantContext}，而查元数据正是为了确定用哪个库——
 * 传进去就是先有鸡还是先有蛋的死循环。理由与 {@code JdbcMigrationStore} 相同。
 *
 * <p><b>建表</b>：DDL 见 {@link #ddl()}，用标准类型，MySQL / PostgreSQL / H2 可直接执行。
 * 与 {@code JdbcMigrationStore} 一样不在构造函数里自动建表——生产库通常不给应用 DDL 权限，
 * 多实例同时启动还会撞上建表竞争。需要时显式调用 {@link #ensureTable()}。
 *
 * <p><b>多实例</b>：本类解决的是"数据持久化"，不是"缓存跨实例同步"。A 实例改了库，
 * B 实例的本地缓存要等 TTL 过期才看到——这是最终一致，收敛时间由 TTL 决定。
 * 迁移这种强一致要求的场景，靠 {@code MigrationCoordinator} 的
 * {@code onMetadataChanged} 回调在每个实例上失效缓存。
 */
public final class JdbcMetadataProvider implements MutableMetadataProvider {

    private static final Logger log = LoggerFactory.getLogger(JdbcMetadataProvider.class);

    public static final String DEFAULT_TABLE = "tenant_shard";

    /** 表名无法用 PreparedStatement 参数化，只能白名单校验后拼接。 */
    private static final Pattern SAFE_TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static final String COLUMNS =
            "tenant_id, shard_type, ds_key, logical_group, status, updated_at";

    private final DataSource dataSource;
    private final String table;

    public JdbcMetadataProvider(DataSource dataSource) {
        this(dataSource, DEFAULT_TABLE);
    }

    /**
     * @param dataSource 元数据库连接，<b>不能</b>是 tenant-grid 的路由数据源
     * @param table      表名，只允许 {@code [A-Za-z_][A-Za-z0-9_]*}
     */
    public JdbcMetadataProvider(DataSource dataSource, String table) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.table = validateTableName(table);
    }

    /** 建表语句。用标准类型，MySQL / PostgreSQL / H2 可直接执行。 */
    public static String ddl() {
        return ddl(DEFAULT_TABLE);
    }

    public static String ddl(String table) {
        return """
                CREATE TABLE IF NOT EXISTS %s (
                    tenant_id     VARCHAR(64)   NOT NULL PRIMARY KEY,
                    shard_type    VARCHAR(16)   NOT NULL,
                    ds_key        VARCHAR(128),
                    logical_group VARCHAR(128),
                    status        VARCHAR(32)   NOT NULL,
                    updated_at    TIMESTAMP     NOT NULL
                )""".formatted(validateTableName(table));
    }

    /** 显式建表。幂等（{@code IF NOT EXISTS}）。 */
    public void ensureTable() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(ddl(table))) {
            ps.execute();
            log.info("Ensured metadata table '{}' exists", table);
        } catch (SQLException e) {
            throw new TenantGridException("Failed to create metadata table '" + table + "'", e);
        }
    }

    @Override
    public TenantShard find(String tenantId) {
        if (tenantId == null) {
            return null;
        }
        String sql = "SELECT " + COLUMNS + " FROM " + table + " WHERE tenant_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? toShard(rs) : null;
            }
        } catch (SQLException e) {
            throw failure("load shard of tenant '" + tenantId + "'", e);
        }
    }

    @Override
    public void register(TenantShard shard) {
        if (shard == null) {
            return;
        }
        if (update(shard) > 0) {
            return;
        }
        try {
            insert(shard);
        } catch (SQLException e) {
            // 并发下另一实例可能刚插入同一租户：退回 UPDATE，仍不成功才是真故障
            if (update(shard) > 0) {
                return;
            }
            throw failure("register shard of tenant '" + shard.tenantId() + "'", e);
        }
    }

    @Override
    public void unregister(String tenantId) {
        if (tenantId == null) {
            return;
        }
        String sql = "DELETE FROM " + table + " WHERE tenant_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("unregister tenant '" + tenantId + "'", e);
        }
    }

    /** 全部租户，供管理台与运维查询。 */
    public List<TenantShard> all() {
        String sql = "SELECT " + COLUMNS + " FROM " + table + " ORDER BY tenant_id";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            List<TenantShard> shards = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    shards.add(toShard(rs));
                }
            }
            return shards;
        } catch (SQLException e) {
            throw failure("list shards", e);
        }
    }

    private int update(TenantShard shard) {
        String sql = "UPDATE " + table + " SET shard_type = ?, ds_key = ?, logical_group = ?, "
                + "status = ?, updated_at = ? WHERE tenant_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            bindValues(ps, shard, 1);
            ps.setString(6, shard.tenantId());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("update shard of tenant '" + shard.tenantId() + "'", e);
        }
    }

    private void insert(TenantShard shard) throws SQLException {
        String sql = "INSERT INTO " + table + " (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, shard.tenantId());
            bindValues(ps, shard, 2);
            ps.executeUpdate();
        }
    }

    /** 绑定除 tenant_id 外的 5 列；tenant_id 的位置因 INSERT / UPDATE 而异，由调用方绑定。 */
    private void bindValues(PreparedStatement ps, TenantShard shard, int start) throws SQLException {
        ps.setString(start, shard.shardType().name());
        ps.setString(start + 1, shard.dsKey());
        ps.setString(start + 2, shard.logicalGroup());
        ps.setString(start + 3, shard.status().name());
        ps.setTimestamp(start + 4, Timestamp.from(Instant.now()));
    }

    private TenantShard toShard(ResultSet rs) throws SQLException {
        String tenantId = rs.getString("tenant_id");
        try {
            return new TenantShard(
                    tenantId,
                    ShardType.valueOf(rs.getString("shard_type")),
                    rs.getString("ds_key"),
                    rs.getString("logical_group"),
                    TenantStatus.valueOf(rs.getString("status")));
        } catch (RuntimeException e) {
            // 库里的行不满足分片形态约束（如 LOGICAL 却没有 logicalGroup）时，
            // 给出能定位到具体租户的信息，而不是 TenantShard 的通用校验消息
            throw new IllegalStateException(
                    "Corrupt shard row for tenant '" + tenantId + "' in table '" + table + "'", e);
        }
    }

    private TenantGridException failure(String operation, SQLException cause) {
        return new TenantGridException(
                "Failed to " + operation + " in metadata table '" + table + "'", cause);
    }

    private static String validateTableName(String table) {
        Objects.requireNonNull(table, "table must not be null");
        if (!SAFE_TABLE_NAME.matcher(table).matches()) {
            throw new IllegalArgumentException(
                    "Invalid metadata table name '" + table + "': must match [A-Za-z_][A-Za-z0-9_]*");
        }
        return table;
    }
}
