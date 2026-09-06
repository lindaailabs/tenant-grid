package io.github.lindaailabs.tenantgrid.core;

import io.github.lindaailabs.tenantgrid.core.exception.MissingTenantContextException;
import io.github.lindaailabs.tenantgrid.core.exception.TenantNotFoundException;
import io.github.lindaailabs.tenantgrid.core.exception.UnknownDataSourceException;

import java.util.Objects;

/**
 * 路由内核：租户 → 数据源。
 *
 * <p>混合分治的全部决策集中在这里：
 * <ul>
 *   <li>{@link ShardType#PHYSICAL} 直接返回元数据里的 dsKey</li>
 *   <li>{@link ShardType#LOGICAL}  交给 {@link LogicalShardResolver} 算出共享库节点</li>
 * </ul>
 *
 * <p>无状态、线程安全，可作为单例使用。
 */
public final class TenantRouter {

    private final MetadataProvider metadataProvider;
    private final LogicalShardResolver logicalShardResolver;

    public TenantRouter(MetadataProvider metadataProvider, LogicalShardResolver logicalShardResolver) {
        this.metadataProvider = Objects.requireNonNull(metadataProvider, "metadataProvider must not be null");
        this.logicalShardResolver = Objects.requireNonNull(logicalShardResolver, "logicalShardResolver must not be null");
    }

    /**
     * 解析租户的目标数据源。
     *
     * @throws MissingTenantContextException 租户为空
     * @throws TenantNotFoundException       元数据中不存在该租户
     * @throws UnknownDataSourceException    逻辑分组未配置或解析结果为空
     */
    public ShardPlan route(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new MissingTenantContextException();
        }

        TenantShard shard = metadataProvider.find(tenantId);
        if (shard == null) {
            throw new TenantNotFoundException(tenantId);
        }

        String dsKey = (shard.shardType() == ShardType.PHYSICAL)
                ? shard.dsKey()
                : logicalShardResolver.resolve(tenantId, shard.logicalGroup());

        if (dsKey == null || dsKey.isBlank()) {
            throw new UnknownDataSourceException(
                    "Resolved an empty dsKey for tenant '" + tenantId + "' (shardType=" + shard.shardType() + ")");
        }

        return new ShardPlan(dsKey, shard.shardType(), shard.status());
    }

    /** 解析当前线程绑定租户的目标数据源。 */
    public ShardPlan routeCurrent() {
        return route(TenantContext.currentId());
    }
}
