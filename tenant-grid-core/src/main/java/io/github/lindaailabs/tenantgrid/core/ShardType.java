package io.github.lindaailabs.tenantgrid.core;

/**
 * 租户数据的落库方式。
 *
 * <p>混合分治的两种形态：
 * <ul>
 *   <li>{@link #PHYSICAL} 大客户独占物理库，靠物理隔离保 SLA</li>
 *   <li>{@link #LOGICAL}  长尾客户共享逻辑库，靠 tenant_id 分片降成本</li>
 * </ul>
 */
public enum ShardType {

    /** 独占物理库：一个租户对应一个独立 DataSource。 */
    PHYSICAL,

    /** 共享逻辑库：多个租户共用一个 DataSource，靠 tenant_id 区分。 */
    LOGICAL
}
