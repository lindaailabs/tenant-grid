package io.github.lindaailabs.tenantgrid.core.migration;

import io.github.lindaailabs.tenantgrid.core.InMemoryMetadataProvider;
import io.github.lindaailabs.tenantgrid.core.ShardType;
import io.github.lindaailabs.tenantgrid.core.TenantShard;
import io.github.lindaailabs.tenantgrid.core.TenantStatus;
import io.github.lindaailabs.tenantgrid.core.exception.MigrationException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcMigrationStoreTest {

    private DataSource dataSource;
    private JdbcMigrationStore store;

    @BeforeEach
    void setUp() {
        dataSource = h2DataSource();
        store = new JdbcMigrationStore(dataSource);
        store.ensureTable();
    }

    /** 每个测试一个独立内存库，避免相互污染。 */
    private static JdbcDataSource h2DataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    private static MigrationTask task(String tenantId, MigrationStage stage) {
        return task(tenantId, stage, Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    /** H2 的 TIMESTAMP 精度只到微秒，纳秒位会被截断；截断到毫秒后往返可比。 */
    private static MigrationTask task(String tenantId, MigrationStage stage, Instant updatedAt) {
        return new MigrationTask(tenantId, "ds_std_0", "ds_vip_1",
                TenantShard.logical(tenantId, "std"), stage, "detail of " + stage, updatedAt);
    }

    @Test
    void roundTripsLogicalSourceShard() {
        MigrationTask saved = task("t_1", MigrationStage.DUAL_WRITE);

        store.save(saved);

        MigrationTask loaded = store.find("t_1");
        assertThat(loaded).isNotNull();
        assertThat(loaded.sourceDsKey()).isEqualTo("ds_std_0");
        assertThat(loaded.targetDsKey()).isEqualTo("ds_vip_1");
        assertThat(loaded.stage()).isEqualTo(MigrationStage.DUAL_WRITE);
        assertThat(loaded.detail()).isEqualTo("detail of DUAL_WRITE");
        assertThat(loaded.updatedAt()).isEqualTo(saved.updatedAt());

        // 原始分片是 LOGICAL：只凭 dsKey 无法还原，logicalGroup 必须完整回来
        assertThat(loaded.sourceShard().shardType()).isEqualTo(ShardType.LOGICAL);
        assertThat(loaded.sourceShard().logicalGroup()).isEqualTo("std");
        assertThat(loaded.sourceShard().dsKey()).isNull();
        assertThat(loaded.sourceShard().status()).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    void roundTripsPhysicalSourceShard() {
        store.save(new MigrationTask("vip_1", "ds_vip_1", "ds_vip_2",
                TenantShard.physical("vip_1", "ds_vip_1"), MigrationStage.CATCH_UP, "moving",
                Instant.now().truncatedTo(ChronoUnit.MILLIS)));

        MigrationTask loaded = store.find("vip_1");

        assertThat(loaded.sourceShard().shardType()).isEqualTo(ShardType.PHYSICAL);
        assertThat(loaded.sourceShard().dsKey()).isEqualTo("ds_vip_1");
        assertThat(loaded.sourceShard().logicalGroup()).isNull();
        assertThat(loaded.stage()).isEqualTo(MigrationStage.CATCH_UP);
    }

    /** save 必须是 upsert：协调器每推进一步都保存同一个 tenantId。 */
    @Test
    void repeatedSaveUpdatesRatherThanDuplicates() throws SQLException {
        store.save(task("t_1", MigrationStage.DUAL_WRITE));

        store.save(task("t_1", MigrationStage.CATCH_UP));
        store.save(task("t_1", MigrationStage.VERIFY));

        assertThat(rowCount()).isEqualTo(1);
        assertThat(store.find("t_1").stage()).isEqualTo(MigrationStage.VERIFY);
        assertThat(store.all()).hasSize(1);
    }

    @Test
    void findReturnsNullWhenAbsent() {
        assertThat(store.find("ghost")).isNull();
    }

    @Test
    void removeDropsOnlyThatTenant() {
        store.save(task("t_1", MigrationStage.DUAL_WRITE));
        store.save(task("t_2", MigrationStage.DUAL_WRITE));

        store.remove("t_1");

        assertThat(store.find("t_1")).isNull();
        assertThat(store.find("t_2")).isNotNull();
    }

    @Test
    void allReturnsTasksOrderedByUpdateTime() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        store.save(task("t_2", MigrationStage.DUAL_WRITE, base.plusSeconds(1)));
        store.save(task("t_1", MigrationStage.DUAL_WRITE, base));

        List<MigrationTask> tasks = store.all();

        assertThat(tasks).extracting(MigrationTask::tenantId).containsExactly("t_1", "t_2");
    }

    @Test
    void ensureTableIsIdempotent() {
        store.save(task("t_1", MigrationStage.DUAL_WRITE));

        store.ensureTable();

        assertThat(store.find("t_1")).isNotNull();
    }

    /**
     * 持久化存在的全部理由：协调器重启后，得知道这个租户还在双写期。
     * 换个 coordinator 实例（唯一共享的是 store），进度必须还在。
     */
    @Test
    void migrationProgressSurvivesCoordinatorRestart() {
        InMemoryMetadataProvider metadata = new InMemoryMetadataProvider();
        metadata.register(TenantShard.logical("t_1", "std"));

        MigrationCoordinator first = coordinatorWith(metadata);
        first.start("t_1", "ds_std_0", "ds_vip_1");
        first.advance("t_1");   // DUAL_WRITE -> CATCH_UP
        assertThat(first.shouldDualWrite("t_1")).isTrue();

        MigrationCoordinator restarted = coordinatorWith(metadata);
        assertThat(restarted.status("t_1").stage()).isEqualTo(MigrationStage.CATCH_UP);
        assertThat(restarted.shouldDualWrite("t_1")).isTrue();

        assertThat(restarted.advance("t_1").stage()).isEqualTo(MigrationStage.VERIFY);
        assertThat(restarted.advance("t_1").stage()).isEqualTo(MigrationStage.CUT_OVER);
        assertThat(restarted.advance("t_1").stage()).isEqualTo(MigrationStage.COMPLETED);
        assertThat(metadata.find("t_1").dsKey()).isEqualTo("ds_vip_1");
    }

    private MigrationCoordinator coordinatorWith(InMemoryMetadataProvider metadata) {
        return new MigrationCoordinator(metadata, store,
                (tenantId, source, target) -> 100L,
                (tenantId, source, target) -> MigrationVerifier.MigrationCheck.ok("counts match"),
                () -> {
                });
    }

    /** SQL 失败要包装成带租户信息的异常，而不是裸 SQLException 漏出去。 */
    @Test
    void wrapsSqlFailureWithTenantContext() {
        JdbcMigrationStore missingTable = new JdbcMigrationStore(h2DataSource());

        assertThatThrownBy(() -> missingTable.save(task("t_1", MigrationStage.DUAL_WRITE)))
                .isInstanceOf(MigrationException.class)
                .hasMessageContaining("t_1")
                .hasCauseInstanceOf(SQLException.class);
    }

    /** 库里的行不满足分片形态约束时，要能定位到具体租户。 */
    @Test
    void corruptRowIdentifiesTheTenant() throws Exception {
        insertRawRow("t_bad", "LOGICAL", null, null);   // LOGICAL 却没有 logicalGroup

        assertThatThrownBy(() -> store.find("t_bad"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("t_bad");
    }

    /** 表名拼进 SQL 且无法参数化，只接受白名单字符。 */
    @Test
    void rejectsTableNameThatCouldInjectSql() {
        assertThatThrownBy(() -> new JdbcMigrationStore(h2DataSource(), "migrations; DROP TABLE users"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid migration table name");
    }

    private void insertRawRow(String tenantId, String shardType, String shardDsKey, String logicalGroup)
            throws SQLException {
        String sql = "INSERT INTO " + JdbcMigrationStore.DEFAULT_TABLE
                + " (tenant_id, source_ds_key, target_ds_key, source_shard_type, "
                + "source_shard_ds_key, source_shard_logical_group, source_shard_status, "
                + "stage, detail, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, "ds_std_0");
            ps.setString(3, "ds_vip_1");
            ps.setString(4, shardType);
            ps.setString(5, shardDsKey);
            ps.setString(6, logicalGroup);
            ps.setString(7, TenantStatus.ACTIVE.name());
            ps.setString(8, MigrationStage.DUAL_WRITE.name());
            ps.setString(9, "raw");
            ps.setTimestamp(10, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    private int rowCount() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT COUNT(*) FROM " + JdbcMigrationStore.DEFAULT_TABLE)) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
