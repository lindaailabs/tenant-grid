package io.github.lindaailabs.tenantgrid.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CachingMetadataProviderTest {

    private static final Duration TTL = Duration.ofSeconds(30);
    private static final Duration NEGATIVE_TTL = Duration.ofSeconds(5);

    private CountingProvider delegate;
    private MutableClock clock;

    /** 可推进的时钟：让 TTL 测试不需要真的 sleep。 */
    private static final class MutableClock extends Clock {

        private long millis = 1_000_000L;

        void advance(Duration duration) {
            millis += duration.toMillis();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }

    private static final class CountingProvider implements MetadataProvider {

        private final Map<String, TenantShard> data = new ConcurrentHashMap<>();
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public TenantShard find(String tenantId) {
            calls.incrementAndGet();
            return data.get(tenantId);
        }

        int calls() {
            return calls.get();
        }
    }

    @BeforeEach
    void setUp() {
        delegate = new CountingProvider();
        clock = new MutableClock();
    }

    private CachingMetadataProvider caching() {
        return new CachingMetadataProvider(delegate, TTL, NEGATIVE_TTL, 100, clock);
    }

    @Test
    void servesRepeatedLookupsFromCache() {
        delegate.data.put("t_1", TenantShard.logical("t_1", "std"));
        CachingMetadataProvider provider = caching();

        assertThat(provider.find("t_1")).isNotNull();
        assertThat(provider.find("t_1")).isNotNull();
        assertThat(provider.find("t_1")).isNotNull();

        assertThat(delegate.calls()).isEqualTo(1);
    }

    @Test
    void reloadsAfterTtlExpires() {
        delegate.data.put("t_1", TenantShard.logical("t_1", "std"));
        CachingMetadataProvider provider = caching();

        provider.find("t_1");
        clock.advance(TTL.plusMillis(1));

        provider.find("t_1");

        assertThat(delegate.calls()).isEqualTo(2);
    }

    @Test
    void stillFreshJustBeforeTtl() {
        delegate.data.put("t_1", TenantShard.logical("t_1", "std"));
        CachingMetadataProvider provider = caching();

        provider.find("t_1");
        clock.advance(TTL.minusMillis(1));
        provider.find("t_1");

        assertThat(delegate.calls()).isEqualTo(1);
    }

    /** 负缓存：不存在的租户必须被缓存，否则每个非法请求都会穿透到后端。 */
    @Test
    void cachesAbsentTenants() {
        CachingMetadataProvider provider = caching();

        assertThat(provider.find("ghost")).isNull();
        assertThat(provider.find("ghost")).isNull();

        assertThat(delegate.calls()).isEqualTo(1);
    }

    @Test
    void absentEntriesExpireSoonerThanPresentOnes() {
        delegate.data.put("present", TenantShard.logical("present", "std"));
        CachingMetadataProvider provider = caching();

        provider.find("present");
        provider.find("absent");

        // 越过 negativeTtl(5s)，尚未越过 ttl(30s)
        clock.advance(Duration.ofSeconds(6));

        provider.find("present");   // 命中
        provider.find("absent");    // 负缓存已过期，回源

        assertThat(delegate.calls()).isEqualTo(3);
    }

    @Test
    void invalidateForcesReloadBeforeTtl() {
        delegate.data.put("t_1", TenantShard.logical("t_1", "std"));
        CachingMetadataProvider provider = caching();

        provider.find("t_1");
        provider.invalidate("t_1");
        provider.find("t_1");

        assertThat(delegate.calls()).isEqualTo(2);
    }

    @Test
    void invalidateAllClearsEveryEntry() {
        delegate.data.put("t_1", TenantShard.logical("t_1", "std"));
        delegate.data.put("t_2", TenantShard.logical("t_2", "std"));
        CachingMetadataProvider provider = caching();

        provider.find("t_1");
        provider.find("t_2");
        assertThat(provider.stats().size()).isEqualTo(2);

        provider.invalidateAll();

        assertThat(provider.stats().size()).isZero();
        provider.find("t_1");
        provider.find("t_2");
        assertThat(delegate.calls()).isEqualTo(4);
    }

    @Test
    void evictsWhenMaxSizeIsReached() {
        CachingMetadataProvider provider =
                new CachingMetadataProvider(delegate, Duration.ofMinutes(5), Duration.ofMinutes(5), 4, clock);

        for (int i = 0; i < 20; i++) {
            delegate.data.put("t_" + i, TenantShard.logical("t_" + i, "std"));
            provider.find("t_" + i);
        }

        // 必须是有界的，否则租户 churn 会让它无上限增长
        assertThat(provider.stats().size()).isLessThanOrEqualTo(4);
        assertThat(provider.stats().size()).isGreaterThan(0);
    }

    @Test
    void tracksHitRate() {
        delegate.data.put("t_1", TenantShard.logical("t_1", "std"));
        CachingMetadataProvider provider = caching();

        provider.find("t_1");   // miss
        provider.find("t_1");   // hit
        provider.find("t_1");   // hit

        CachingMetadataProvider.CacheStats stats = provider.stats();
        assertThat(stats.hits()).isEqualTo(2);
        assertThat(stats.misses()).isEqualTo(1);
        assertThat(stats.size()).isEqualTo(1);
        assertThat(stats.hitRate()).isEqualTo(2.0 / 3.0);
    }

    @Test
    void hitRateIsZeroWhenUntouched() {
        assertThat(caching().stats().hitRate()).isZero();
    }

    /** 缓存击穿保护：同一 key 并发未命中时，后端只被打一次。 */
    @Test
    void concurrentMissesTriggerOnlyOneBackendCall() throws Exception {
        delegate.data.put("t_1", TenantShard.logical("t_1", "std"));
        CachingMetadataProvider provider = caching();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TenantShard>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return provider.find("t_1");
                }));
            }
            start.countDown();
            for (Future<TenantShard> result : results) {
                assertThat(result.get(5, TimeUnit.SECONDS)).isNotNull();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(delegate.calls()).isEqualTo(1);
    }

    @Test
    void nullTenantIsNotQueried() {
        assertThat(caching().find(null)).isNull();
        assertThat(delegate.calls()).isZero();
    }

    @Test
    void doesNotCacheBackendFailures() {
        MetadataProvider broken = tenantId -> {
            throw new IllegalStateException("backend unavailable");
        };
        CachingMetadataProvider provider =
                new CachingMetadataProvider(broken, TTL, NEGATIVE_TTL, 10, clock);

        assertThatThrownBy(() -> provider.find("t_1"))
                .isInstanceOf(IllegalStateException.class);

        // 异常不能被当成"租户不存在"缓存下来，否则后端恢复后仍然一直报错
        assertThat(provider.stats().size()).isZero();
    }

    private static final class CountingMutableProvider implements MutableMetadataProvider {

        private final Map<String, TenantShard> data = new ConcurrentHashMap<>();
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public TenantShard find(String tenantId) {
            calls.incrementAndGet();
            return data.get(tenantId);
        }

        @Override
        public void register(TenantShard shard) {
            data.put(shard.tenantId(), shard);
        }

        @Override
        public void unregister(String tenantId) {
            data.remove(tenantId);
        }

        int calls() {
            return calls.get();
        }
    }

    /** 切流时若不失效本地条目，写完立刻读会拿到切流前的旧分片——路由打回源库。 */
    @Test
    void registerWritesThroughAndRefreshesLocalEntry() {
        CountingMutableProvider mutable = new CountingMutableProvider();
        mutable.data.put("t_1", TenantShard.logical("t_1", "std"));
        CachingMetadataProvider provider =
                new CachingMetadataProvider(mutable, TTL, NEGATIVE_TTL, 100, clock);

        assertThat(provider.find("t_1").shardType()).isEqualTo(ShardType.LOGICAL);

        provider.register(TenantShard.physical("t_1", "ds_vip_1"));

        assertThat(mutable.data.get("t_1").dsKey()).isEqualTo("ds_vip_1");   // 写穿到委托
        assertThat(provider.find("t_1").dsKey()).isEqualTo("ds_vip_1");      // 本地缓存已失效
        assertThat(mutable.calls()).isEqualTo(2);
    }

    @Test
    void unregisterWritesThroughAndRefreshesLocalEntry() {
        CountingMutableProvider mutable = new CountingMutableProvider();
        mutable.data.put("t_1", TenantShard.logical("t_1", "std"));
        CachingMetadataProvider provider =
                new CachingMetadataProvider(mutable, TTL, NEGATIVE_TTL, 100, clock);

        assertThat(provider.find("t_1")).isNotNull();

        provider.unregister("t_1");

        assertThat(mutable.data).doesNotContainKey("t_1");
        assertThat(provider.find("t_1")).isNull();
    }

    /** 委托只读时，写方法必须在调用处就明确失败，而不是静默丢弃。 */
    @Test
    void writeIsRejectedWhenDelegateIsImmutable() {
        CachingMetadataProvider provider = caching();   // delegate 是只读的 CountingProvider

        assertThatThrownBy(() -> provider.register(TenantShard.logical("t_1", "std")))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("not a MutableMetadataProvider");

        assertThatThrownBy(() -> provider.unregister("t_1"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("not a MutableMetadataProvider");
    }

    @Test
    void rejectsNonPositiveMaxSize() {
        assertThatThrownBy(() -> new CachingMetadataProvider(delegate, TTL, NEGATIVE_TTL, 0, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSize");
    }
}
