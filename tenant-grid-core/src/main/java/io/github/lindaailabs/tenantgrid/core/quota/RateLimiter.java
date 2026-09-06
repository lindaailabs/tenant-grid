package io.github.lindaailabs.tenantgrid.core.quota;

/**
 * 租户限流器（QPS 维度）。
 *
 * <p>与 {@link QuotaGuard} 是两个正交的维度，缺一不可：
 * <ul>
 *   <li>{@code QuotaGuard} 管<b>并发数</b>——挡住"一次占着很多连接不放"的慢租户</li>
 *   <li>{@code RateLimiter} 管<b>速率</b>——挡住"瞬时打来海量请求"的突发流量</li>
 * </ul>
 *
 * <p>只挡并发不挡速率的话，短平快的高频请求照样能把下游打垮。
 */
public interface RateLimiter {

    /**
     * 尝试消耗一个令牌。
     *
     * @return true 表示放行；false 表示已超过该租户的速率上限
     */
    boolean tryAcquire(String tenantId);

    /** 每秒生成的令牌数（即稳态 QPS 上限）。 */
    double permitsPerSecond();
}
