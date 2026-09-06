package io.github.lindaailabs.tenantgrid.core.migration;

import io.github.lindaailabs.tenantgrid.core.InMemoryMetadataProvider;
import io.github.lindaailabs.tenantgrid.core.ShardType;
import io.github.lindaailabs.tenantgrid.core.TenantShard;
import io.github.lindaailabs.tenantgrid.core.TenantStatus;
import io.github.lindaailabs.tenantgrid.core.exception.MigrationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationCoordinatorTest {

    private InMemoryMetadataProvider metadata;
    private InMemoryMigrationStore store;
    private StubDataMover dataMover;
    private StubVerifier verifier;
    private final AtomicInteger cacheInvalidations = new AtomicInteger();
    private MigrationCoordinator coordinator;

    private static final class StubDataMover implements DataMover {

        private long rows = 100L;
        private boolean fail;
        private int calls;

        @Override
        public long move(String tenantId, String sourceDsKey, String targetDsKey) throws Exception {
            calls++;
            if (fail) {
                throw new IllegalStateException("data move failed");
            }
            return rows;
        }
    }

    private static final class StubVerifier implements MigrationVerifier {

        private boolean consistent = true;
        private String detail = "row counts match";
        private int calls;

        @Override
        public MigrationCheck verify(String tenantId, String sourceDsKey, String targetDsKey) {
            calls++;
            return consistent ? MigrationCheck.ok(detail) : MigrationCheck.mismatch(detail);
        }
    }

    @BeforeEach
    void setUp() {
        metadata = new InMemoryMetadataProvider();
        store = new InMemoryMigrationStore();
        dataMover = new StubDataMover();
        verifier = new StubVerifier();
        coordinator = new MigrationCoordinator(metadata, store, dataMover, verifier,
                cacheInvalidations::incrementAndGet);
    }

    private void planMigration() {
        metadata.register(TenantShard.logical("t_1", "std"));
        coordinator.start("t_1", "ds_std_0", "ds_vip_1");
    }

    @Test
    void startMarksTenantAsMigrating() {
        metadata.register(TenantShard.logical("t_1", "std"));

        MigrationTask task = coordinator.start("t_1", "ds_std_0", "ds_vip_1");

        assertThat(task.stage()).isEqualTo(MigrationStage.DUAL_WRITE);
        assertThat(metadata.find("t_1").status()).isEqualTo(TenantStatus.MIGRATING);
        assertThat(cacheInvalidations.get()).isPositive();
    }

    @Test
    void walksThroughEveryStageToCompletion() {
        planMigration();
        assertThat(coordinator.shouldDualWrite("t_1")).isTrue();

        assertThat(coordinator.advance("t_1").stage()).isEqualTo(MigrationStage.CATCH_UP);
        assertThat(coordinator.advance("t_1").stage()).isEqualTo(MigrationStage.VERIFY);
        assertThat(coordinator.advance("t_1").stage()).isEqualTo(MigrationStage.CUT_OVER);
        MigrationTask done = coordinator.advance("t_1");

        assertThat(done.stage()).isEqualTo(MigrationStage.COMPLETED);
        assertThat(done.isTerminal()).isTrue();

        // 切流后租户指向独立物理库，且不再是迁移中
        TenantShard shard = metadata.find("t_1");
        assertThat(shard.shardType()).isEqualTo(ShardType.PHYSICAL);
        assertThat(shard.dsKey()).isEqualTo("ds_vip_1");
        assertThat(shard.status()).isEqualTo(TenantStatus.ACTIVE);

        assertThat(coordinator.shouldDualWrite("t_1")).isFalse();
        assertThat(dataMover.calls).isEqualTo(1);
        assertThat(verifier.calls).isEqualTo(1);
    }

    @Test
    void dualWriteIsExpectedOnlyDuringEarlyStages() {
        planMigration();
        assertThat(coordinator.shouldDualWrite("t_1")).isTrue();     // DUAL_WRITE

        coordinator.advance("t_1");                                   // CATCH_UP
        assertThat(coordinator.shouldDualWrite("t_1")).isTrue();

        coordinator.advance("t_1");                                   // VERIFY
        assertThat(coordinator.shouldDualWrite("t_1")).isFalse();
    }

    /** 校验不过绝不能切流——切完才发现不一致，代价是数据损坏。 */
    @Test
    void stopsAtFailedWhenVerificationFails() {
        planMigration();
        verifier.consistent = false;
        verifier.detail = "source 100 rows, target 97 rows";

        coordinator.advance("t_1");                       // -> CATCH_UP
        coordinator.advance("t_1");                       // -> VERIFY
        MigrationTask failed = coordinator.advance("t_1"); // -> FAILED

        assertThat(failed.stage()).isEqualTo(MigrationStage.FAILED);
        assertThat(failed.detail()).contains("97 rows");
        assertThat(metadata.find("t_1").shardType()).isEqualTo(ShardType.LOGICAL);
        assertThat(metadata.find("t_1").dsKey()).isNull();
    }

    @Test
    void marksFailedWhenDataMoveThrows() {
        planMigration();
        dataMover.fail = true;

        coordinator.advance("t_1");                       // -> CATCH_UP
        assertThatThrownBy(() -> coordinator.advance("t_1"))
                .isInstanceOf(MigrationException.class)
                .hasMessageContaining("CATCH_UP");

        assertThat(coordinator.status("t_1").stage()).isEqualTo(MigrationStage.FAILED);
    }

    @Test
    void rollbackRestoresOriginalLogicalShard() {
        planMigration();

        MigrationTask rolledBack = coordinator.rollback("t_1");

        assertThat(rolledBack.stage()).isEqualTo(MigrationStage.ROLLED_BACK);
        TenantShard shard = metadata.find("t_1");
        assertThat(shard.shardType()).isEqualTo(ShardType.LOGICAL);
        assertThat(shard.logicalGroup()).isEqualTo("std");
        assertThat(shard.status()).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    void rollbackIsIdempotent() {
        planMigration();
        coordinator.rollback("t_1");

        assertThat(coordinator.rollback("t_1").stage()).isEqualTo(MigrationStage.ROLLED_BACK);
    }

    @Test
    void rejectsUnknownTenant() {
        assertThatThrownBy(() -> coordinator.start("ghost", "ds_std_0", "ds_vip_1"))
                .isInstanceOf(MigrationException.class)
                .hasMessageContaining("no shard metadata");
    }

    @Test
    void rejectsIdenticalSourceAndTarget() {
        metadata.register(TenantShard.physical("vip_1", "ds_vip_1"));

        assertThatThrownBy(() -> coordinator.start("vip_1", "ds_vip_1", "ds_vip_1"))
                .isInstanceOf(MigrationException.class)
                .hasMessageContaining("same datasource");
    }

    @Test
    void rejectsAdvanceWithoutTask() {
        assertThatThrownBy(() -> coordinator.advance("ghost"))
                .isInstanceOf(MigrationException.class)
                .hasMessageContaining("no migration task");
    }

    @Test
    void refusesToAdvanceFromTerminalStage() {
        planMigration();
        coordinator.rollback("t_1");

        assertThatThrownBy(() -> coordinator.advance("t_1"))
                .isInstanceOf(MigrationException.class)
                .hasMessageContaining("terminal stage");
    }

    @Test
    void listsOnlyActiveTasks() {
        planMigration();
        assertThat(coordinator.activeTasks()).hasSize(1);

        coordinator.rollback("t_1");

        assertThat(coordinator.activeTasks()).isEmpty();
    }
}
