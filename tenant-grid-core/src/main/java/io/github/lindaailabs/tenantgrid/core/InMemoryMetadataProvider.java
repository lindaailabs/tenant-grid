package io.github.lindaailabs.tenantgrid.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存实现，用于测试和轻量部署（元数据来自配置文件）。
 *
 * <p>支持运行时增删，便于验证租户迁移与动态路由，但不具备持久化和跨实例同步能力。
 */
public final class InMemoryMetadataProvider implements MutableMetadataProvider {

    private final Map<String, TenantShard> shards;

    public InMemoryMetadataProvider(Map<String, TenantShard> shards) {
        this.shards = new ConcurrentHashMap<>();
        if (shards != null) {
            shards.values().forEach(this::register);
        }
    }

    public InMemoryMetadataProvider() {
        this(Map.of());
    }

    @Override
    public TenantShard find(String tenantId) {
        return (tenantId == null) ? null : shards.get(tenantId);
    }

    /** 注册或覆盖一个租户的分片元数据。 */
    @Override
    public void register(TenantShard shard) {
        if (shard == null) {
            return;
        }
        shards.put(shard.tenantId(), shard);
    }

    /** 移除租户的元数据。 */
    @Override
    public void unregister(String tenantId) {
        shards.remove(tenantId);
    }

    /** 当前已注册的租户数量。 */
    public int size() {
        return shards.size();
    }

    /** 返回快照，避免外部修改内部状态。 */
    public Map<String, TenantShard> snapshot() {
        return new LinkedHashMap<>(shards);
    }
}
