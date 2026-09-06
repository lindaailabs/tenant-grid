package io.github.lindaailabs.tenantgrid.core.migration;

import io.github.lindaailabs.tenantgrid.core.CachingMetadataProvider;
import io.github.lindaailabs.tenantgrid.core.JdbcMetadataProvider;
import io.github.lindaailabs.tenantgrid.core.LogicalShardResolver;
import io.github.lindaailabs.tenantgrid.core.ShardType;
import io.github.lindaailabs.tenantgrid.core.TenantRouter;
import io.github.lindaailabs.tenantgrid.core.TenantShard;
import io.github.lindaailabs.tenantgrid.core.TenantStatus;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移的持久化端到端：迁移状态在 {@code tenant_grid_migration} 表，
 * 租户的 MIGRATING 标记在 {@code tenant_shard} 表，两者缺一都不算"迁移能跨重启"。
 *
 * <p>只持久化迁移任务是不够的：标记若存在内存里，重启后路由层不再认为该租户处于
 * 双写期，双写静默停止，而迁移任务本身还停在 CATCH_UP——目标库少掉整段数据。
 */
class MigrationPersistenceTest {

    private static final Duration TTL = Duration.ofSeconds(30);
    private static final Duration NEGATIVE_TTL = Duration.ofSeconds(5);

    private DataSource metaDataSource;

    private final DataMover mover = (tenantId, source, target) -> 100L;
    private final MigrationVerifier verifier =
            (tenantId, source, target) -> MigrationVerifier.MigrationCheck.ok("counts match");

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:persistence_" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        metaDataSource = ds;
    }

    /**
     * 迁移进行中重启：新实例必须看到"这个租户正在迁移"，而不是当成普通租户路由。
     *
     * <p>少了元数据持久化，第二、三个断言会分别是 ACTIVE 和 false——
     * 也就是双写悄悄停了。
     */
    @Test
    void migratingMarkerSurvivesRestart() {
        Instance before = boot();
        before.metadata.register(TenantShard.logical("t_1", "std"));
        before.coordinator.start("t_1", "ds_std_0", "ds_vip_1");

        // 进程重启：全新实例，缓存是空的，共享同一个元数据库
        Instance after = boot();

        assertThat(after.metadata.find("t_1").status()).isEqualTo(TenantStatus.MIGRATING);
        assertThat(after.coordinator.status("t_1").stage()).isEqualTo(MigrationStage.DUAL_WRITE);
        assertThat(after.coordinator.shouldDualWrite("t_1")).isTrue();
    }

    /** 切流结果必须对其它实例的路由可见——包括那些缓存里还留着旧分片的实例。 */
    @Test
    void cutOverIsVisibleToRoutingInAnotherInstance() {
        Instance before = boot();
        before.metadata.register(TenantShard.logical("t_1", "std"));
        before.coordinator.start("t_1", "ds_std_0", "ds_vip_1");

        Instance during = boot();
        int steps = 0;
        while (!during.coordinator.status("t_1").isTerminal() && steps++ < 10) {
            during.coordinator.advance("t_1");
        }
        assertThat(during.coordinator.status("t_1").stage()).isEqualTo(MigrationStage.COMPLETED);

        // 又一个实例：缓存全新，只能从库里读到切流后的分片
        LogicalShardResolver sharedGroup = (tenantId, group) -> "ds_std_0";
        TenantRouter router = new TenantRouter(boot().metadata, sharedGroup);

        assertThat(router.route("t_1").dsKey()).isEqualTo("ds_vip_1");
        assertThat(router.route("t_1").shardType()).isEqualTo(ShardType.PHYSICAL);
        assertThat(router.route("t_1").status()).isEqualTo(TenantStatus.ACTIVE);
    }

    /** 模拟一个进程实例：独立的元数据缓存、独立的协调器，共享同一个元数据库。 */
    private Instance boot() {
        return new Instance();
    }

    private final class Instance {

        private final CachingMetadataProvider metadata;
        private final JdbcMigrationStore store;
        private final MigrationCoordinator coordinator;

        Instance() {
            JdbcMetadataProvider jdbc = new JdbcMetadataProvider(metaDataSource);
            jdbc.ensureTable();
            metadata = new CachingMetadataProvider(jdbc, TTL, NEGATIVE_TTL, 1000);
            store = new JdbcMigrationStore(metaDataSource);
            store.ensureTable();
            coordinator = new MigrationCoordinator(metadata, store, mover, verifier,
                    metadata::invalidateAll);
        }
    }
}
