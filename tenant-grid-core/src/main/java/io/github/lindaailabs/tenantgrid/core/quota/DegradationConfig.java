package io.github.lindaailabs.tenantgrid.core.quota;

/**
 * 自动降级策略配置。
 *
 * <p>思路：不预先假设谁会是吵闹租户，而是<b>看实际表现</b>——
 * 某个租户持续被配额拒绝，说明它确实在超量消耗共享资源，
 * 就逐步压低它的配额；它恢复正常后，再逐步放回来。
 *
 * <p>降级是<b>惩罚性收敛</b>而非永久封禁：一旦连续若干次评估都是干净的，
 * 就会自动回升一级，避免一次抖动把租户永久钉死在低配额上。
 */
public record DegradationConfig(
        boolean enabled,
        long evaluationIntervalMillis,
        int minPermits,
        double rejectionRatioThreshold,
        int recoveryAfterCleanEvaluations) {

    /** 关闭降级：配额始终保持基准值。 */
    public static final DegradationConfig DISABLED =
            new DegradationConfig(false, 60_000L, 1, 0.5d, 3);

    public DegradationConfig {
        // 0 是合法值，表示"每次请求都评估"——仅用于测试或极短窗口的部署
        if (evaluationIntervalMillis < 0) {
            throw new IllegalArgumentException(
                    "evaluationIntervalMillis must not be negative, got " + evaluationIntervalMillis);
        }
        if (minPermits <= 0) {
            throw new IllegalArgumentException("minPermits must be positive, got " + minPermits);
        }
        if (rejectionRatioThreshold < 0 || rejectionRatioThreshold > 1) {
            throw new IllegalArgumentException(
                    "rejectionRatioThreshold must be between 0 and 1, got " + rejectionRatioThreshold);
        }
        if (recoveryAfterCleanEvaluations <= 0) {
            throw new IllegalArgumentException(
                    "recoveryAfterCleanEvaluations must be positive, got " + recoveryAfterCleanEvaluations);
        }
    }

    public static DegradationConfig enabled(long evaluationIntervalMillis,
                                            int minPermits,
                                            double rejectionRatioThreshold,
                                            int recoveryAfterCleanEvaluations) {
        return new DegradationConfig(true, evaluationIntervalMillis, minPermits,
                rejectionRatioThreshold, recoveryAfterCleanEvaluations);
    }
}
