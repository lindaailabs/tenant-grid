package io.github.lindaailabs.tenantgrid.core.quota;

/**
 * 单个租户的资源用量快照。
 *
 * <p>重点看三个指标：
 * <ul>
 *   <li>{@code rejected} 持续增长 → 该租户并发被打满，要么它确实超限需要治理，
 *       要么配额配小了</li>
 *   <li>{@code slowHolds} 持续增长 → 该租户存在慢查询，是拖垮共享库的主要嫌疑</li>
 *   <li>{@code penaltyLevel} &gt; 0 → 已被自动降级，{@code permits} 低于基准值</li>
 *   <li>{@code effectiveTimeoutSeconds} 随降级同步收紧；配合 {@code slowHolds} 判断慢查询</li>
 * </ul>
 */
public record TenantUsage(
        String tenantId,
        int permits,
        int active,
        long acquired,
        long rejected,
        long slowHolds,
        long maxHoldMillis,
        int penaltyLevel,
        int effectiveTimeoutSeconds) {

    /** 未启用 SQL 超时时的兼容构造。 */
    public TenantUsage(String tenantId,
                       int permits,
                       int active,
                       long acquired,
                       long rejected,
                       long slowHolds,
                       long maxHoldMillis,
                       int penaltyLevel) {
        this(tenantId, permits, active, acquired, rejected, slowHolds, maxHoldMillis,
                penaltyLevel, 0);
    }

    /** 许可使用率，0.0 ~ 1.0。接近 1 说明该租户快把配额吃满了。 */
    public double utilisation() {
        return (permits <= 0) ? 0.0 : (double) active / permits;
    }
}
