package io.github.lindaailabs.tenantgrid.core;

import io.github.lindaailabs.tenantgrid.core.exception.TenantGridException;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcMetadataProviderTest {

    private DataSource dataSource;
    private JdbcMetadataProvider provider;

    @BeforeEach
    void setUp() {
        dataSource = h2DataSource();
        provider = new JdbcMetadataProvider(dataSource);
        provider.ensureTable();
    }

    /** 每个测试一个独立内存库，避免相互污染。 */
    private static JdbcDataSource h2DataSource() {
        return h2DataSource(UUID.randomUUID().toString().replace("-", ""));
    }

    private static JdbcDataSource h2DataSource(String name) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    @Test
    void roundTripsLogicalShard() {
        provider.register(TenantShard.logical("t_1", "std"));

        TenantShard loaded = provider.find("t_1");

        assertThat(loaded).isNotNull();
        assertThat(loaded.tenantId()).isEqualTo("t_1");
        assertThat(loaded.shardType()).isEqualTo(ShardType.LOGICAL);
        assertThat(loaded.logicalGroup()).isEqualTo("std");
        assertThat(loaded.dsKey()).isNull();
        assertThat(loaded.status()).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    void roundTripsPhysicalShard() {
        provider.register(TenantShard.physical("vip_1", "ds_vip_1"));

        TenantShard loaded = provider.find("vip_1");

        assertThat(loaded.shardType()).isEqualTo(ShardType.PHYSICAL);
        assertThat(loaded.dsKey()).isEqualTo("ds_vip_1");
        assertThat(loaded.logicalGroup()).isNull();
    }

    /** 迁移标记必须存得住——内存实现丢的就是它。 */
    @Test
    void preservesMigratingStatus() {
        provider.register(TenantShard.logical("t_1", "std").withStatus(TenantStatus.MIGRATING));

        assertThat(provider.find("t_1").status()).isEqualTo(TenantStatus.MIGRATING);
    }

    @Test
    void registerUpdatesRatherThanDuplicates() throws SQLException {
        provider.register(TenantShard.logical("t_1", "std"));

        provider.register(TenantShard.physical("t_1", "ds_vip_1"));

        assertThat(rowCount()).isEqualTo(1);
        assertThat(provider.find("t_1").shardType()).isEqualTo(ShardType.PHYSICAL);
        assertThat(provider.find("t_1").dsKey()).isEqualTo("ds_vip_1");
    }

    @Test
    void findReturnsNullForUnknownTenant() {
        assertThat(provider.find("ghost")).isNull();
    }

    @Test
    void findReturnsNullForNullTenant() {
        assertThat(provider.find(null)).isNull();
    }

    @Test
    void unregisterRemovesOnlyThatTenant() {
        provider.register(TenantShard.logical("t_1", "std"));
        provider.register(TenantShard.logical("t_2", "std"));

        provider.unregister("t_1");

        assertThat(provider.find("t_1")).isNull();
        assertThat(provider.find("t_2")).isNotNull();
    }

    @Test
    void allListsEveryShardOrderedByTenantId() {
        provider.register(TenantShard.logical("t_2", "std"));
        provider.register(TenantShard.logical("t_1", "std"));

        List<TenantShard> shards = provider.all();

        assertThat(shards).extracting(TenantShard::tenantId).containsExactly("t_1", "t_2");
    }

    @Test
    void ensureTableIsIdempotent() {
        provider.register(TenantShard.logical("t_1", "std"));

        provider.ensureTable();

        assertThat(provider.find("t_1")).isNotNull();
    }

    /** SQL 失败要包装成 TenantGrid 异常，而不是裸 SQLException 漏出去。 */
    @Test
    void wrapsSqlFailure() {
        JdbcMetadataProvider missingTable = new JdbcMetadataProvider(h2DataSource());

        assertThatThrownBy(() -> missingTable.find("t_1"))
                .isInstanceOf(TenantGridException.class)
                .hasMessageContaining("tenant 't_1'")
                .hasCauseInstanceOf(SQLException.class);
    }

    /** 库里的行不满足分片形态约束时，要能定位到具体租户。 */
    @Test
    void corruptRowIdentifiesTheTenant() throws Exception {
        insertRawRow("t_bad", "LOGICAL", null, null);   // LOGICAL 却没有 logical_group

        assertThatThrownBy(() -> provider.find("t_bad"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("t_bad");
    }

    /** 表名拼进 SQL 且无法参数化，只接受白名单字符。 */
    @Test
    void rejectsTableNameThatCouldInjectSql() {
        assertThatThrownBy(() -> new JdbcMetadataProvider(h2DataSource(), "tenant_shard; DROP TABLE users"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid metadata table name");
    }

    private void insertRawRow(String tenantId, String shardType, String dsKey, String logicalGroup)
            throws SQLException {
        String sql = "INSERT INTO " + JdbcMetadataProvider.DEFAULT_TABLE
                + " (tenant_id, shard_type, ds_key, logical_group, status, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, shardType);
            ps.setString(3, dsKey);
            ps.setString(4, logicalGroup);
            ps.setString(5, TenantStatus.ACTIVE.name());
            ps.setTimestamp(6, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    private int rowCount() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT COUNT(*) FROM " + JdbcMetadataProvider.DEFAULT_TABLE)) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
