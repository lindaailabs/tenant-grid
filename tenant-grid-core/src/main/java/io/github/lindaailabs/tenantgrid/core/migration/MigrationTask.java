package io.github.lindaailabs.tenantgrid.core.migration;

import io.github.lindaailabs.tenantgrid.core.TenantShard;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次租户迁移的进度快照。
 *
 * <p>不可变：每次推进产生新实例，便于审计与故障复盘。
 *
 * <p>同时保存 {@code sourceShard}（原始分片定义）与 {@code sourceDsKey}
 * （解析后的源库）：前者用于回滚时<b>完整还原</b>——原始分片可能是 LOGICAL，
 * 只凭一个 dsKey 无法还原；后者用于数据搬迁和校验。
 */
public record MigrationTask(
        String tenantId,
        String sourceDsKey,
        String targetDsKey,
        TenantShard sourceShard,
        MigrationStage stage,
        String detail,
        Instant updatedAt) {

    public MigrationTask {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(sourceDsKey, "sourceDsKey must not be null");
        Objects.requireNonNull(targetDsKey, "targetDsKey must not be null");
        Objects.requireNonNull(sourceShard, "sourceShard must not be null");
        Objects.requireNonNull(stage, "stage must not be null");
        updatedAt = (updatedAt == null) ? Instant.now() : updatedAt;
    }

    public static MigrationTask create(String tenantId,
                                       TenantShard sourceShard,
                                       String sourceDsKey,
                                       String targetDsKey) {
        return new MigrationTask(tenantId, sourceDsKey, targetDsKey, sourceShard,
                MigrationStage.INIT, "migration planned", Instant.now());
    }

    public MigrationTask at(MigrationStage newStage, String newDetail) {
        return new MigrationTask(tenantId, sourceDsKey, targetDsKey, sourceShard,
                newStage, newDetail, Instant.now());
    }

    /** 是否已进入终态。 */
    public boolean isTerminal() {
        return stage == MigrationStage.COMPLETED
                || stage == MigrationStage.ROLLED_BACK
                || stage == MigrationStage.FAILED;
    }
}
