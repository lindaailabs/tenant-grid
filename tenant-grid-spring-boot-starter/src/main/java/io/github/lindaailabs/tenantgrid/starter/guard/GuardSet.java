package io.github.lindaailabs.tenantgrid.starter.guard;

import io.github.lindaailabs.tenantgrid.core.quota.QuotaGuard;
import io.github.lindaailabs.tenantgrid.core.quota.RateLimiter;

/**
 * 一组可插拔的防护组件，均为可选。
 *
 * <p>收成一个对象是为了避免 {@code RoutingDataSource} 的构造器参数无限膨胀——
 * 每加一种防护就多一个参数，很快就变成谁都不敢动的长参数列表。
 *
 * <p>三层防护各自独立、可单独开关：
 * <ul>
 *   <li>{@code sqlGuard}——防跨租户数据污染（正确性）</li>
 *   <li>{@code quotaGuard}——防单租户占满并发（可用性）</li>
 *   <li>{@code rateLimiter}——防单租户瞬时突发（可用性）</li>
 * </ul>
 */
public record GuardSet(SqlGuard sqlGuard, QuotaGuard quotaGuard, RateLimiter rateLimiter) {

    public static final GuardSet NONE = new GuardSet(null, null, null);

    public static GuardSet of(SqlGuard sqlGuard, QuotaGuard quotaGuard, RateLimiter rateLimiter) {
        return new GuardSet(sqlGuard, quotaGuard, rateLimiter);
    }
}
