package io.github.lindaailabs.tenantgrid.core.quota;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantQuotaGuardTest {

    private static final long HIGH_THRESHOLD = 10_000L;
    private static final int MAX_TRACKED = 100;

    private TenantUsage usageOf(TenantQuotaGuard guard, String tenantId) {
        return guard.usage().stream()
                .filter(usage -> usage.tenantId().equals(tenantId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no usage recorded for " + tenantId));
    }

    @Test
    void grantsUpToThePermitLimit() {
        TenantQuotaGuard guard = new TenantQuotaGuard(2, HIGH_THRESHOLD, MAX_TRACKED);

        assertThat(guard.tryAcquire("t_1")).isTrue();
        assertThat(guard.tryAcquire("t_1")).isTrue();
        assertThat(guard.tryAcquire("t_1")).isFalse();
    }

    @Test
    void releaseFreesAPermit() {
        TenantQuotaGuard guard = new TenantQuotaGuard(1, HIGH_THRESHOLD, MAX_TRACKED);
        guard.tryAcquire("t_1");

        guard.release("t_1");

        assertThat(guard.tryAcquire("t_1")).isTrue();
    }

    /** 核心目标：一个租户打满配额，不影响同库其他租户。 */
    @Test
    void isolatesTenantsFromEachOther() {
        TenantQuotaGuard guard = new TenantQuotaGuard(1, HIGH_THRESHOLD, MAX_TRACKED);
        assertThat(guard.tryAcquire("noisy")).isTrue();

        assertThat(guard.tryAcquire("quiet")).isTrue();
        assertThat(guard.tryAcquire("noisy")).isFalse();
    }

    @Test
    void nullTenantIsNeverLimited() {
        TenantQuotaGuard guard = new TenantQuotaGuard(1, HIGH_THRESHOLD, MAX_TRACKED);

        assertThat(guard.tryAcquire(null)).isTrue();
        assertThat(guard.tryAcquire(null)).isTrue();
    }

    @Test
    void reportsUsagePerTenant() {
        TenantQuotaGuard guard = new TenantQuotaGuard(2, HIGH_THRESHOLD, MAX_TRACKED);
        guard.tryAcquire("t_1");
        guard.tryAcquire("t_1");
        guard.tryAcquire("t_1");   // 超限被拒

        TenantUsage usage = usageOf(guard, "t_1");
        assertThat(usage.permits()).isEqualTo(2);
        assertThat(usage.active()).isEqualTo(2);
        assertThat(usage.acquired()).isEqualTo(2);
        assertThat(usage.rejected()).isEqualTo(1);
        assertThat(usage.utilisation()).isEqualTo(1.0);
    }

    @Test
    void permitsPerTenantIsExposed() {
        assertThat(new TenantQuotaGuard(7, HIGH_THRESHOLD, MAX_TRACKED).permitsPerTenant()).isEqualTo(7);
    }

    @Test
    void recordsMaxHoldTime() {
        TenantQuotaGuard guard = new TenantQuotaGuard(1, HIGH_THRESHOLD, MAX_TRACKED);
        guard.tryAcquire("t_1");

        guard.recordHoldTime("t_1", 120);
        guard.recordHoldTime("t_1", 450);
        guard.recordHoldTime("t_1", 80);

        assertThat(usageOf(guard, "t_1").maxHoldMillis()).isEqualTo(450);
    }

    @Test
    void countsSlowHoldsAboveThreshold() {
        // 阈值 -1：任何持有（含 0ms）都算慢，避免依赖真实耗时
        TenantQuotaGuard guard = new TenantQuotaGuard(1, -1L, MAX_TRACKED);
        guard.tryAcquire("t_1");

        guard.recordHoldTime("t_1", 0);
        guard.recordHoldTime("t_1", 5);

        assertThat(usageOf(guard, "t_1").slowHolds()).isEqualTo(2);
    }

    @Test
    void ignoresFastHolds() {
        TenantQuotaGuard guard = new TenantQuotaGuard(1, 1_000L, MAX_TRACKED);
        guard.tryAcquire("t_1");

        guard.recordHoldTime("t_1", 10);

        assertThat(usageOf(guard, "t_1").slowHolds()).isZero();
    }

    @Test
    void releasesWithoutPriorAcquireAreHarmless() {
        TenantQuotaGuard guard = new TenantQuotaGuard(1, HIGH_THRESHOLD, MAX_TRACKED);

        guard.release("never-acquired");

        assertThat(guard.usage()).isEmpty();
    }

    /** 租户 churn 不能让跟踪表无界增长。 */
    @Test
    void evictsIdleSlotsBeyondMaxTrackedTenants() {
        TenantQuotaGuard guard = new TenantQuotaGuard(1, HIGH_THRESHOLD, 2);

        guard.tryAcquire("t_1");                       // 保持占用
        guard.tryAcquire("t_2");
        guard.release("t_2");
        guard.tryAcquire("t_3");
        guard.release("t_3");
        guard.tryAcquire("t_4");                       // 触发清理

        List<TenantUsage> usage = guard.usage();
        assertThat(usage).hasSizeLessThanOrEqualTo(2);

        // 仍被占用的租户绝不能被清理掉
        assertThat(usage).anySatisfy(entry -> {
            assertThat(entry.tenantId()).isEqualTo("t_1");
            assertThat(entry.active()).isEqualTo(1);
        });
    }

    @Test
    void rejectsNonPositivePermits() {
        assertThatThrownBy(() -> new TenantQuotaGuard(0, HIGH_THRESHOLD, MAX_TRACKED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permitsPerTenant");
    }

    @Test
    void rejectsNonPositiveMaxTrackedTenants() {
        assertThatThrownBy(() -> new TenantQuotaGuard(1, HIGH_THRESHOLD, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxTrackedTenants");
    }

    /** 间隔 0 表示每次请求都评估，避免测试依赖真实等待。 */
    private static DegradationConfig alwaysEvaluate() {
        return DegradationConfig.enabled(0L, 1, 0.2d, 3);
    }

    @Test
    void degradesTenantThatKeepsGettingRejected() {
        TenantQuotaGuard guard = new TenantQuotaGuard(8, HIGH_THRESHOLD, 100, alwaysEvaluate());

        for (int i = 0; i < 8; i++) {
            guard.tryAcquire("noisy");
        }
        for (int i = 0; i < 6; i++) {
            guard.tryAcquire("noisy");
        }

        assertThat(usageOf(guard, "noisy").penaltyLevel()).isGreaterThan(0);
        assertThat(guard.effectivePermits("noisy")).isLessThan(8);
    }

    @Test
    void leavesWellBehavedTenantsAlone() {
        TenantQuotaGuard guard = new TenantQuotaGuard(8, HIGH_THRESHOLD, 100, alwaysEvaluate());

        for (int i = 0; i < 5; i++) {
            guard.tryAcquire("quiet");
        }

        assertThat(usageOf(guard, "quiet").penaltyLevel()).isZero();
        assertThat(guard.effectivePermits("quiet")).isEqualTo(8);
    }

    @Test
    void recoversAfterTenantBehavesAgain() {
        TenantQuotaGuard guard = new TenantQuotaGuard(8, HIGH_THRESHOLD, 100,
                DegradationConfig.enabled(0L, 1, 0.2d, 2));

        for (int i = 0; i < 8; i++) {
            guard.tryAcquire("t_1");
        }
        // 降级在下一次评估时才生效（要先把拒绝记录下来），所以得制造多次拒绝
        for (int i = 0; i < 5; i++) {
            guard.tryAcquire("t_1");
        }
        int degradedLevel = usageOf(guard, "t_1").penaltyLevel();
        assertThat(degradedLevel).isGreaterThan(0);

        for (int i = 0; i < 8; i++) {
            guard.release("t_1");
        }
        for (int i = 0; i < 20; i++) {
            guard.tryAcquire("t_1");
            guard.release("t_1");
        }

        assertThat(usageOf(guard, "t_1").penaltyLevel()).isLessThan(degradedLevel);
    }

    @Test
    void keepsBaseQuotaWhenDegradationIsDisabled() {
        TenantQuotaGuard guard =
                new TenantQuotaGuard(2, HIGH_THRESHOLD, 100, DegradationConfig.DISABLED);

        guard.tryAcquire("t_1");
        guard.tryAcquire("t_1");
        guard.tryAcquire("t_1");
        guard.tryAcquire("t_1");

        assertThat(guard.effectivePermits("t_1")).isEqualTo(2);
        assertThat(usageOf(guard, "t_1").penaltyLevel()).isZero();
    }

    @Test
    void neverDegradesBelowMinPermits() {
        TenantQuotaGuard guard =
                new TenantQuotaGuard(8, HIGH_THRESHOLD, 100, DegradationConfig.enabled(0L, 3, 0.2d, 99));

        for (int i = 0; i < 8; i++) {
            guard.tryAcquire("t_1");
        }
        for (int i = 0; i < 20; i++) {
            guard.tryAcquire("t_1");
        }

        assertThat(guard.effectivePermits("t_1")).isGreaterThanOrEqualTo(3);
    }

    private static TenantQuotaGuard guardWithTimeout(int baseSeconds, int minSeconds) {
        return new TenantQuotaGuard(8, HIGH_THRESHOLD, 100, alwaysEvaluate(), baseSeconds, minSeconds);
    }

    @Test
    void noStatementTimeoutByDefault() {
        TenantQuotaGuard guard = new TenantQuotaGuard(8, HIGH_THRESHOLD, MAX_TRACKED);

        assertThat(guard.effectiveTimeoutSeconds("t_1")).isZero();
    }

    @Test
    void statementTimeoutAppliesBeforeAnyDegradation() {
        assertThat(guardWithTimeout(30, 5).effectiveTimeoutSeconds("t_1")).isEqualTo(30);
    }

    /** 超时与配额共用惩罚档位：降一级，超时也砍一半。 */
    @Test
    void statementTimeoutShrinksWithPenaltyLevel() {
        TenantQuotaGuard guard = guardWithTimeout(30, 1);

        for (int i = 0; i < 8; i++) {
            guard.tryAcquire("noisy");
        }
        for (int i = 0; i < 6; i++) {
            guard.tryAcquire("noisy");
        }

        int penalty = usageOf(guard, "noisy").penaltyLevel();
        assertThat(penalty).isGreaterThan(0);
        assertThat(guard.effectiveTimeoutSeconds("noisy")).isEqualTo(30 >> penalty);
    }

    /** 0 在 JDBC 里表示不限时，所以压到最狠也必须留 1 秒——否则降级等于解除限制。 */
    @Test
    void statementTimeoutNeverDropsBelowFloor() {
        TenantQuotaGuard guard = guardWithTimeout(30, 5);

        for (int i = 0; i < 8; i++) {
            guard.tryAcquire("t_1");
        }
        for (int i = 0; i < 30; i++) {
            guard.tryAcquire("t_1");
        }

        assertThat(guard.effectiveTimeoutSeconds("t_1")).isGreaterThanOrEqualTo(5);
    }

    @Test
    void usageExposesEffectiveTimeout() {
        TenantQuotaGuard guard = guardWithTimeout(30, 5);
        guard.tryAcquire("t_1");

        assertThat(usageOf(guard, "t_1").effectiveTimeoutSeconds()).isEqualTo(30);
    }

    @Test
    void rejectsNegativeStatementTimeout() {
        assertThatThrownBy(() -> new TenantQuotaGuard(1, HIGH_THRESHOLD, 100, alwaysEvaluate(), -1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("statementTimeoutSeconds");
    }

    @Test
    void rejectsNegativeMinStatementTimeout() {
        assertThatThrownBy(() -> new TenantQuotaGuard(1, HIGH_THRESHOLD, 100, alwaysEvaluate(), 30, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minStatementTimeoutSeconds");
    }
}
