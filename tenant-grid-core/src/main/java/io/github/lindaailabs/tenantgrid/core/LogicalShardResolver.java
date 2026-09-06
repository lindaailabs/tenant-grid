package io.github.lindaailabs.tenantgrid.core;

/**
 * 逻辑分片解析器：把「共享库组 + 租户」换算成具体的数据源节点。
 *
 * <p>独立出来是因为这块策略最容易变——hash 取模、一致性 hash、按租户等级加权，
 * 每种的扩容代价不同。路由内核不关心具体算法。
 */
public interface LogicalShardResolver {

    /**
     * 解析租户在共享库组中的目标节点。
     *
     * @param tenantId     租户标识
     * @param logicalGroup 共享库组名
     * @return 目标数据源的 key
     * @throws io.github.lindaailabs.tenantgrid.core.exception.UnknownDataSourceException 分组未配置
     */
    String resolve(String tenantId, String logicalGroup);
}
