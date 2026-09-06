package io.github.lindaailabs.tenantgrid.core;

/**
 * 支持运行时改写的元数据提供者。
 *
 * <p>只读的 {@link MetadataProvider} 无法满足迁移需求——切流那一刻必须
 * 把租户的分片指向从源库改成目标库，这是<b>写操作</b>。
 */
public interface MutableMetadataProvider extends MetadataProvider {

    /** 注册或覆盖租户的分片元数据。 */
    void register(TenantShard shard);

    /** 移除租户的元数据。 */
    void unregister(String tenantId);
}
