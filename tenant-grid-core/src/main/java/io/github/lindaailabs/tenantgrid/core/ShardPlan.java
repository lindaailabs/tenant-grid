package io.github.lindaailabs.tenantgrid.core;

/**
 * 路由结果：一次租户解析最终落到哪个数据源。
 *
 * <p>{@link ShardType#LOGICAL} 的租户在这里已经拿到了确定节点——
 * 逻辑分组到具体库的换算发生在路由阶段，而不是 DataSource 查找阶段。
 */
public record ShardPlan(String dsKey, ShardType shardType, TenantStatus status) {

    public ShardPlan {
        if (dsKey == null || dsKey.isBlank()) {
            throw new IllegalArgumentException("dsKey must not be null or blank");
        }
        if (shardType == null) {
            throw new IllegalArgumentException("shardType must not be null");
        }
        status = (status == null) ? TenantStatus.ACTIVE : status;
    }
}
