package io.github.lindaailabs.tenantgrid.core.quota;

import java.util.List;

/**
 * 租户资源配额守卫——防吵闹邻居。
 *
 * <p>大客户独占物理库已经解决了物理隔离，但<b>共享逻辑库内部</b>的长尾租户
 * 仍然共用同一个连接池：一个租户的慢查询或突发流量占满连接，
 * 同库所有租户一起不可用。
 *
 * <p>这里在数据源出口做每层租户的并发配额：超限直接快速失败，
 * 而不是让它把连接池耗干后再让所有请求一起排队超时。
 */
public interface QuotaGuard {

    /**
     * 尝试为租户占用一个许可（通常对应一条连接）。
     *
     * @return true 表示获得许可；false 表示已达该租户上限，调用方应快速失败
     */
    boolean tryAcquire(String tenantId);

    /** 归还许可。必须与 {@link #tryAcquire} 成对调用，通常绑定在连接关闭时。 */
    void release(String tenantId);

    /**
     * 记录一次持有连接的时长，用于发现慢租户。
     *
     * @param heldMillis 从获取到关闭的毫秒数
     */
    void recordHoldTime(String tenantId, long heldMillis);

    /** 每个租户的基准并发许可上限。 */
    int permitsPerTenant();

    /** 租户当前生效的许可上限；被自动降级后会低于基准值。 */
    int effectivePermits(String tenantId);

    /**
     * 租户当前生效的单条 SQL 执行超时（秒）；<b>0 表示不限制</b>。
     *
     * <p>与并发配额互补：配额挡的是"同时占着多少条连接"，超时挡的是"一条查询占着连接多久"。
     * 只有并发限制时，几个慢查询就能把配额吃满——它们不释放，后面的请求全被拒。
     * 有了超时，慢查询会自己让位，而不是一直占着。
     *
     * <p>被自动降级的租户，超时与配额共用一套惩罚档位同步收紧。
     *
     * <p>默认返回 0（不限制）：不做超时控制的实现无需关心这个方法。
     */
    default int effectiveTimeoutSeconds(String tenantId) {
        return 0;
    }

    /** 当前各租户的用量快照，用于监控与容量规划。 */
    List<TenantUsage> usage();
}
