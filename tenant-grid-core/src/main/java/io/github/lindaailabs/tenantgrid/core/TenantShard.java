package io.github.lindaailabs.tenantgrid.core;

import java.util.Objects;

/**
 * 租户的分片元数据，对应一张 {@code tenant_shard} 表的行。
 *
 * <p>不可变值对象。两种形态互斥且各自必填：
 * <ul>
 *   <li>{@link ShardType#PHYSICAL} 必须提供 {@code dsKey}</li>
 *   <li>{@link ShardType#LOGICAL}  必须提供 {@code logicalGroup}</li>
 * </ul>
 *
 * <pre>{@code
 * TenantShard.physical("t_vip_1", "ds_vip_1");   // 大客户 → 独立库
 * TenantShard.logical("t_0001", "std");          // 长尾   → 共享库组
 * }</pre>
 */
public record TenantShard(
        String tenantId,
        ShardType shardType,
        String dsKey,
        String logicalGroup,
        TenantStatus status) {

    public TenantShard {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(shardType, "shardType must not be null");
        status = (status == null) ? TenantStatus.ACTIVE : status;

        if (shardType == ShardType.PHYSICAL) {
            if (dsKey == null || dsKey.isBlank()) {
                throw new IllegalArgumentException(
                        "PHYSICAL shard requires a non-blank dsKey, tenantId=" + tenantId);
            }
        } else if (logicalGroup == null || logicalGroup.isBlank()) {
            throw new IllegalArgumentException(
                    "LOGICAL shard requires a non-blank logicalGroup, tenantId=" + tenantId);
        }
    }

    /** 独占物理库的租户。 */
    public static TenantShard physical(String tenantId, String dsKey) {
        return new TenantShard(tenantId, ShardType.PHYSICAL, dsKey, null, TenantStatus.ACTIVE);
    }

    /** 共享逻辑库的租户，具体节点由 {@link LogicalShardResolver} 计算。 */
    public static TenantShard logical(String tenantId, String logicalGroup) {
        return new TenantShard(tenantId, ShardType.LOGICAL, null, logicalGroup, TenantStatus.ACTIVE);
    }

    /** 返回同配置、状态改为 {@code newStatus} 的副本（迁移流程使用）。 */
    public TenantShard withStatus(TenantStatus newStatus) {
        return new TenantShard(tenantId, shardType, dsKey, logicalGroup, newStatus);
    }
}
