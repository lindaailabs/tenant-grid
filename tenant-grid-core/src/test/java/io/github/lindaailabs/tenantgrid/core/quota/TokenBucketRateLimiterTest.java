package io.github.lindaailabs.tenantgrid.core.quota;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenBucketRateLimiterTest {

    /**
     * 补充速率取一个"两头都安全"的值：
     * 2ms 能补满桶（5000 × 0.002 = 10 个令牌，远超容量），
     * 但两次相邻调用之间的微秒间隔补不满（需要 0.2ms 才够 1 个令牌）。
     * 速率再高（如 1e6/s）会让"间隔补不满"这个前提失效，测试变成竞态。
     */
    private static final double FAST_REFILL = 5_000.0d;

    @Test
    void allowsBurstUpToBucketCapacity() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1.0d, 3.0d, 100);

        assertThat(limiter.tryAcquire("t_1")).isTrue();
        assertThat(limiter.tryAcquire("t_1")).isTrue();
        assertThat(limiter.tryAcquire("t_1")).isTrue();
        assertThat(limiter.tryAcquire("t_1")).isFalse();
    }

    @Test
    void refillsTokensOverTime() throws Exception {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(FAST_REFILL, 1.0d, 100);

        assertThat(limiter.tryAcquire("t_1")).isTrue();
        assertThat(limiter.tryAcquire("t_1")).isFalse();

        Thread.sleep(2);   // 2ms × 1e6/s 远超桶容量

        assertThat(limiter.tryAcquire("t_1")).isTrue();
    }

    @Test
    void isolatesTenantsFromEachOther() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1.0d, 1.0d, 100);

        assertThat(limiter.tryAcquire("noisy")).isTrue();
        assertThat(limiter.tryAcquire("quiet")).isTrue();
        assertThat(limiter.tryAcquire("noisy")).isFalse();
    }

    @Test
    void nullTenantIsNeverLimited() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1.0d, 1.0d, 100);

        assertThat(limiter.tryAcquire(null)).isTrue();
        assertThat(limiter.tryAcquire(null)).isTrue();
    }

    @Test
    void exposesConfiguredRate() {
        assertThat(new TokenBucketRateLimiter(42.0d, 10.0d, 100).permitsPerSecond()).isEqualTo(42.0d);
    }

    @Test
    void tracksTenantsSeparately() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1.0d, 1.0d, 100);

        limiter.tryAcquire("t_1");
        limiter.tryAcquire("t_2");

        assertThat(limiter.trackedTenants()).isEqualTo(2);
    }

    /** 租户 churn 不能让限流器的跟踪表无界增长。 */
    @Test
    void boundsTrackedTenants() throws Exception {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(FAST_REFILL, 5.0d, 2);

        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("t_" + i);
            Thread.sleep(2);   // 让桶补满，成为可清理的候选
        }

        assertThat(limiter.trackedTenants()).isLessThanOrEqualTo(2);
    }

    @Test
    void rejectsNonPositiveRate() {
        assertThatThrownBy(() -> new TokenBucketRateLimiter(0d, 1.0d, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permitsPerSecond");
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> new TokenBucketRateLimiter(1.0d, 0d, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("burstCapacity");
    }

    @Test
    void rejectsNonPositiveMaxTrackedTenants() {
        assertThatThrownBy(() -> new TokenBucketRateLimiter(1.0d, 1.0d, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxTrackedTenants");
    }
}
