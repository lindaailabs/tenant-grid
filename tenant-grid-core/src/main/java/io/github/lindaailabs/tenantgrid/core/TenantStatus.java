package io.github.lindaailabs.tenantgrid.core;

/**
 * 租户的路由状态。
 *
 * <p>{@link #MIGRATING} 由迁移协调器设置：{@code MigrationCoordinator.start()} 把租户
 * 标记为迁移中，路由层据此知道该租户正处于双写期；切流完成或回滚后恢复 {@link #ACTIVE}。
 *
 * <p>注意状态只存在于元数据提供者里，而默认实现是内存版——重启后 MIGRATING 标记会丢。
 * 迁移期间的状态持久化见 {@code MigrationStore}。
 */
public enum TenantStatus {

    /** 正常服务。 */
    ACTIVE,

    /** 迁移中（升降级 / 数据搬迁）。 */
    MIGRATING
}
