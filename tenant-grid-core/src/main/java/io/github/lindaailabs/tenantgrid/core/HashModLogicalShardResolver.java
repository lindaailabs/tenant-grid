package io.github.lindaailabs.tenantgrid.core;

import io.github.lindaailabs.tenantgrid.core.exception.UnknownDataSourceException;

import java.util.List;
import java.util.Map;

/**
 * 一致性要求最低、分布最均匀的默认策略：{@code floorMod(hash(tenantId), nodeCount)}。
 *
 * <p>使用 {@link String#hashCode()}：该算法在 JDK 规范中被明确定义，
 * 因此跨 JVM、跨版本结果稳定——这对分片函数至关重要，
 * 否则换一次 JDK 版本数据位置就变了。
 *
 * <p>代价是扩容需要搬迁数据（节点数变化会导致大部分租户重映射），
 * 这正是 {@code migration} 包里 {@code MigrationCoordinator} 要解决的问题——
 * 它没有改用一致性 hash，而是把搬迁做成在线迁移流程。
 */
public final class HashModLogicalShardResolver implements LogicalShardResolver {

    private final Map<String, List<String>> groups;

    public HashModLogicalShardResolver(Map<String, List<String>> groups) {
        Map<String, List<String>> copy = new java.util.LinkedHashMap<>();
        groups.forEach((group, nodes) -> {
            if (nodes == null || nodes.isEmpty()) {
                throw new IllegalArgumentException(
                        "Logical group '" + group + "' must declare at least one node");
            }
            copy.put(group, List.copyOf(nodes));
        });
        this.groups = Map.copyOf(copy);
    }

    @Override
    public String resolve(String tenantId, String logicalGroup) {
        List<String> nodes = groups.get(logicalGroup);
        if (nodes == null) {
            throw new UnknownDataSourceException(
                    "Unknown logical group '" + logicalGroup + "' for tenant '" + tenantId
                            + "'. Configured groups: " + groups.keySet());
        }
        int index = Math.floorMod(tenantId.hashCode(), nodes.size());
        return nodes.get(index);
    }

    /** 已配置的分组名，供错误信息与运维端点使用。 */
    public java.util.Set<String> groups() {
        return groups.keySet();
    }
}
