package io.github.lindaailabs.tenantgrid.core.migration;

import io.github.lindaailabs.tenantgrid.core.ShardType;
import io.github.lindaailabs.tenantgrid.core.TenantShard;
import io.github.lindaailabs.tenantgrid.core.TenantStatus;
import io.github.lindaailabs.tenantgrid.core.exception.MigrationException;
import io.github.lindaailabs.tenantgrid.core.exception.TenantGridException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 迁移任务的数据库持久化实现。
 *
 * <p><b>为什么不能用内存实现上生产</b>：一次迁移要跨 DUAL_WRITE → CATCH_UP → VERIFY →
 * CUT_OVER 多个阶段，中间隔着数据搬迁（DataX 跑完可能要几小时）。进程重启或多实例部署时，
 * 内存版会丢掉"这个租户正在双写"这个事实——后果是路由回到源库、双写静默停止，
 * 目标库少掉整个双写期的数据。{@link InMemoryMigrationStore} 只适合测试。
 *
 * <p><b>必须传入独立的元数据库 {@link DataSource}，绝不能传 tenant-grid 的路由数据源</b>：
 * 路由数据源取连接时要解析 {@code TenantContext}，而迁移协调器是在没有租户上下文的
 * 环境里推进任务的（定时任务 / 运维接口），传进去只会得到
 * {@code MissingTenantContextException}，或在 {@code strict=false} 时静默落到默认库。
 *
 * <p><b>建表</b>：DDL 由 {@link #ddl()} 给出，需用哪个库就按方言微调（本 DDL 用
 * 标准类型，MySQL / PostgreSQL / H2 可直接跑）。建表动作不隐藏在构造函数里——
 * 生产库通常不给应用 DDL 权限，多实例同时启动还会撞上建表竞争。需要时显式调用
 * {@link #ensureTable()}，或把 DDL 交给 DBA / Flyway。
 *
 * <p><b>并发</b>：{@code save} 是 upsert，先 UPDATE 再 INSERT。为什么不直接用
 * {@code INSERT ... ON DUPLICATE KEY UPDATE}：那是 MySQL 方言，PostgreSQL 要写成
 * {@code ON CONFLICT}。这里用两条通用语句，INSERT 撞唯一键时退回 UPDATE，
 * 换来的是不绑定数据库。
 */
public final class JdbcMigrationStore implements MigrationStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcMigrationStore.class);

    public static final String DEFAULT_TABLE = "tenant_grid_migration";

    /** 表名无法用 PreparedStatement 参数化，只能白名单校验后拼接。 */
    private static final Pattern SAFE_TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static final String COLUMNS =
            "tenant_id, source_ds_key, target_ds_key, source_shard_type, source_shard_ds_key, "
                    + "source_shard_logical_group, source_shard_status, stage, detail, updated_at";

    private final DataSource dataSource;
    private final String table;

    public JdbcMigrationStore(DataSource dataSource) {
        this(dataSource, DEFAULT_TABLE);
    }

    /**
     * @param dataSource 元数据库连接，<b>不能</b>是 tenant-grid 的路由数据源
     * @param table      表名，只允许 {@code [A-Za-z_][A-Za-z0-9_]*}
     */
    public JdbcMigrationStore(DataSource dataSource, String table) {
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
                    tenant_id                  VARCHAR(64)   NOT NULL PRIMARY KEY,
                    source_ds_key              VARCHAR(128)  NOT NULL,
                    target_ds_key              VARCHAR(128)  NOT NULL,
                    source_shard_type          VARCHAR(16)   NOT NULL,
                    source_shard_ds_key        VARCHAR(128),
                    source_shard_logical_group VARCHAR(128),
                    source_shard_status        VARCHAR(32)   NOT NULL,
                    stage                      VARCHAR(32)   NOT NULL,
                    detail                     VARCHAR(1024),
                    updated_at                 TIMESTAMP     NOT NULL
                )""".formatted(validateTableName(table));
    }

    /** 显式建表。幂等（{@code IF NOT EXISTS}）。 */
    public void ensureTable() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(ddl(table))) {
            ps.execute();
            log.info("Ensured migration table '{}' exists", table);
        } catch (SQLException e) {
            throw new TenantGridException("Failed to create migration table '" + table + "'", e);
        }
    }

    @Override
    public void save(MigrationTask task) {
        Objects.requireNonNull(task, "task must not be null");
        if (update(task) > 0) {
            return;
        }
        try {
            insert(task);
        } catch (SQLException e) {
            // 并发下另一个实例可能刚插入同一租户：退回 UPDATE，仍不成功才是真故障
            if (update(task) > 0) {
                return;
            }
            throw failure("save", task.tenantId(), e);
        }
    }

    @Override
    public MigrationTask find(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        String sql = "SELECT " + COLUMNS + " FROM " + table + " WHERE tenant_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? toTask(rs) : null;
            }
        } catch (SQLException e) {
            throw failure("load", tenantId, e);
        }
    }

    @Override
    public void remove(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        String sql = "DELETE FROM " + table + " WHERE tenant_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("remove", tenantId, e);
        }
    }

    @Override
    public List<MigrationTask> all() {
        String sql = "SELECT " + COLUMNS + " FROM " + table + " ORDER BY updated_at";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            List<MigrationTask> tasks = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tasks.add(toTask(rs));
                }
            }
            return tasks;
        } catch (SQLException e) {
            // 这里没有具体租户，用表级异常
            throw new TenantGridException(
                    "Failed to list migration tasks from table '" + table + "'", e);
        }
    }

    private int update(MigrationTask task) {
        String sql = "UPDATE " + table + " SET source_ds_key = ?, target_ds_key = ?, "
                + "source_shard_type = ?, source_shard_ds_key = ?, source_shard_logical_group = ?, "
                + "source_shard_status = ?, stage = ?, detail = ?, updated_at = ? "
                + "WHERE tenant_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            bindValues(ps, task, 1);
            ps.setString(10, task.tenantId());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("update", task.tenantId(), e);
        }
    }

    private void insert(MigrationTask task) throws SQLException {
        String sql = "INSERT INTO " + table + " (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, task.tenantId());
            bindValues(ps, task, 2);
            ps.executeUpdate();
        }
    }

    /** 绑定除 tenant_id 外的 9 列；tenant_id 的位置因 INSERT / UPDATE 而异，由调用方绑定。 */
    private void bindValues(PreparedStatement ps, MigrationTask task, int start) throws SQLException {
        TenantShard shard = task.sourceShard();
        ps.setString(start, task.sourceDsKey());
        ps.setString(start + 1, task.targetDsKey());
        ps.setString(start + 2, shard.shardType().name());
        ps.setString(start + 3, shard.dsKey());
        ps.setString(start + 4, shard.logicalGroup());
        ps.setString(start + 5, shard.status().name());
        ps.setString(start + 6, task.stage().name());
        ps.setString(start + 7, task.detail());
        ps.setTimestamp(start + 8, Timestamp.from(task.updatedAt()));
    }

    private MigrationTask toTask(ResultSet rs) throws SQLException {
        String tenantId = rs.getString("tenant_id");
        TenantShard shard;
        try {
            shard = new TenantShard(
                    tenantId,
                    ShardType.valueOf(rs.getString("source_shard_type")),
                    rs.getString("source_shard_ds_key"),
                    rs.getString("source_shard_logical_group"),
                    TenantStatus.valueOf(rs.getString("source_shard_status")));
        } catch (RuntimeException e) {
            // 库里的行不满足分片形态约束（如 LOGICAL 却没有 logicalGroup）时，
            // 给出能定位到具体租户的信息，而不是 TenantShard 的通用校验消息
            throw new IllegalStateException(
                    "Corrupt migration row for tenant '" + tenantId + "' in table '" + table + "'", e);
        }
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new MigrationTask(
                tenantId,
                rs.getString("source_ds_key"),
                rs.getString("target_ds_key"),
                shard,
                MigrationStage.valueOf(rs.getString("stage")),
                rs.getString("detail"),
                updatedAt == null ? null : updatedAt.toInstant());
    }

    private MigrationException failure(String operation, String tenantId, SQLException cause) {
        return new MigrationException(tenantId,
                "failed to " + operation + " migration task in table '" + table + "'", cause);
    }

    private static String validateTableName(String table) {
        Objects.requireNonNull(table, "table must not be null");
        if (!SAFE_TABLE_NAME.matcher(table).matches()) {
            throw new IllegalArgumentException(
                    "Invalid migration table name '" + table + "': must match [A-Za-z_][A-Za-z0-9_]*");
        }
        return table;
    }
}
