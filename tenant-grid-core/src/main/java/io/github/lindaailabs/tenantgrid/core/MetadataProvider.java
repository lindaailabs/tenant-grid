package io.github.lindaailabs.tenantgrid.core;

/**
 * 租户分片元数据的来源（SPI）。
 *
 * <p>路由层只依赖这个接口，不关心数据存在哪：
 * 可以是配置中心、数据库表、HTTP 接口，或测试里的内存实现。
 *
 * <p>实现需要自行保证查询效率——路由在每次获取连接时都会被调用。
 * 生产实现应套上 {@link CachingMetadataProvider}（{@code JdbcMetadataProvider} 尤其必须）。
 */
public interface MetadataProvider {

    /**
     * 查询租户的分片元数据。
     *
     * @param tenantId 租户标识
     * @return 分片元数据；租户不存在时返回 {@code null}
     */
    TenantShard find(String tenantId);
}
