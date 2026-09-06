package io.github.lindaailabs.tenantgrid.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 给任意 {@link MetadataProvider} 加上本地缓存的装饰器。
 *
 * <p>路由发生在<strong>每次获取连接</strong>时，是最热的读路径。
 * 没有缓存的话，一次业务请求就会对配置中心 / 元数据库打上若干次查询。
 *
 * <p>四个必须处理的问题：
 * <ol>
 *   <li><b>TTL</b>——元数据会因租户升降级、迁移而变化，缓存必须有过期时间</li>
 *   <li><b>缓存击穿</b>——同一 key 并发未命中时只让一个线程回源（{@code computeIfAbsent} 保证）</li>
 *   <li><b>负缓存</b>——不存在的租户若不缓存，每次请求都会穿透到后端，
 *       等于给后端开了一个放大流量的口子。用更短的 TTL 单独缓存"不存在"</li>
 *   <li><b>有界</b>——条目无上限就是内存泄漏，租户 churn 会让它只增不减</li>
 * </ol>
 *
 * <p>租户迁移完成后应立即 {@link #invalidate(String)}，而不是等 TTL 自然过期。
 *
 * <p><b>写操作</b>：当委托对象是 {@link MutableMetadataProvider} 时，本类也可当作可变的
 * 提供者使用——{@link #register(TenantShard)} / {@link #unregister(String)} 会先写穿到委托，
 * 再失效本地对应条目，保证"写完立刻读到新值"。委托不可变时调用写方法抛
 * {@link UnsupportedOperationException}（构造已定，不会到运行到一半才失败）。
 *
 * <p>注意写穿只保证<b>本实例</b>一致：其他实例的本地缓存仍要等 TTL 过期才看到变更。
 * 这是分布式的固有取舍，收敛时间由 TTL 决定——跨实例强一致要靠使用方的广播机制
 * （配置中心推送 / Redis pub-sub），不属本库范围。
 */
public final class CachingMetadataProvider implements MutableMetadataProvider {

    private static final Logger log = LoggerFactory.getLogger(CachingMetadataProvider.class);

    public static final Duration DEFAULT_TTL = Duration.ofSeconds(30);
    public static final Duration DEFAULT_NEGATIVE_TTL = Duration.ofSeconds(5);
    public static final int DEFAULT_MAX_SIZE = 100_000;

    private final MetadataProvider delegate;
    /** 委托可变时才非 null；决定 register / unregister 是否可用。 */
    private final MutableMetadataProvider mutableDelegate;
    private final long ttlMillis;
    private final long negativeTtlMillis;
    private final int maxSize;
    private final Clock clock;

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public CachingMetadataProvider(MetadataProvider delegate) {
        this(delegate, DEFAULT_TTL, DEFAULT_NEGATIVE_TTL, DEFAULT_MAX_SIZE, Clock.systemUTC());
    }

    public CachingMetadataProvider(MetadataProvider delegate, Duration ttl, Duration negativeTtl, int maxSize) {
        this(delegate, ttl, negativeTtl, maxSize, Clock.systemUTC());
    }

    public CachingMetadataProvider(MetadataProvider delegate,
                                   Duration ttl,
                                   Duration negativeTtl,
                                   int maxSize,
                                   Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.mutableDelegate = (delegate instanceof MutableMetadataProvider mutable) ? mutable : null;
        this.ttlMillis = Objects.requireNonNull(ttl, "ttl must not be null").toMillis();
        this.negativeTtlMillis = Objects.requireNonNull(negativeTtl, "negativeTtl must not be null").toMillis();
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive, got " + maxSize);
        }
        this.maxSize = maxSize;
    }

    @Override
    public TenantShard find(String tenantId) {
        if (tenantId == null) {
            return null;
        }
        long now = clock.millis();

        CacheEntry entry = cache.get(tenantId);
        if (entry != null && entry.isExpired(now)) {
            // 只有仍是同一个条目才移除，避免误删别的线程刚写入的新值
            cache.remove(tenantId, entry);
            entry = null;
        }
        if (entry != null) {
            hits.incrementAndGet();
            return entry.shard();
        }

        misses.incrementAndGet();
        // computeIfAbsent 保证同一 key 只有一个线程执行回源，其余线程等待并复用结果
        CacheEntry fresh = cache.computeIfAbsent(tenantId, key -> load(key, now));
        evictIfNecessary(now);
        return fresh.shard();
    }

    /**
     * 写穿到委托，再失效本地条目——否则写完立刻 find 会读到缓存里的旧值。
     *
     * @throws UnsupportedOperationException 委托不是 {@link MutableMetadataProvider}
     */
    @Override
    public void register(TenantShard shard) {
        requireMutable("register");
        if (shard == null) {
            return;
        }
        mutableDelegate.register(shard);
        invalidate(shard.tenantId());
    }

    /**
     * 写穿到委托，再失效本地条目。
     *
     * @throws UnsupportedOperationException 委托不是 {@link MutableMetadataProvider}
     */
    @Override
    public void unregister(String tenantId) {
        requireMutable("unregister");
        if (tenantId == null) {
            return;
        }
        mutableDelegate.unregister(tenantId);
        invalidate(tenantId);
    }

    private void requireMutable(String operation) {
        if (mutableDelegate == null) {
            throw new UnsupportedOperationException(
                    "Cannot " + operation + " through CachingMetadataProvider: delegate "
                            + delegate.getClass().getName() + " is not a MutableMetadataProvider");
        }
    }

    /** 使单个租户的缓存失效，下次查询立即回源。租户迁移后调用。 */
    public void invalidate(String tenantId) {
        if (tenantId != null) {
            cache.remove(tenantId);
        }
    }

    /** 清空全部缓存。 */
    public void invalidateAll() {
        cache.clear();
    }

    public CacheStats stats() {
        return new CacheStats(hits.get(), misses.get(), cache.size());
    }

    private CacheEntry load(String tenantId, long nowMillis) {
        TenantShard shard = delegate.find(tenantId);
        long ttl = (shard == null) ? negativeTtlMillis : ttlMillis;
        return new CacheEntry(shard, nowMillis + ttl, nowMillis);
    }

    /**
     * 达到上限时先清过期条目；仍超限则按写入时间淘汰最旧的一批。
     *
     * <p>走到"淘汰未过期条目"这一步说明容量配小了或 TTL 配长了——
     * 这是需要关注的配置问题，因此记 warn。
     */
    private void evictIfNecessary(long now) {
        if (cache.size() < maxSize) {
            return;
        }

        int before = cache.size();
        cache.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        if (cache.size() < maxSize) {
            return;
        }

        int target = maxSize - Math.max(1, maxSize / 4);
        int toEvict = cache.size() - target;
        if (toEvict <= 0) {
            return;
        }

        List<String> oldest = cache.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().createdAtMillis()))
                .limit(toEvict)
                .map(Map.Entry::getKey)
                .toList();
        oldest.forEach(cache::remove);

        log.warn("Metadata cache hit its limit of {} entries ({} before purge); evicted {} live entries. "
                        + "Raise maxSize or lower ttl — evicting live entries means extra backend round-trips.",
                maxSize, before, oldest.size());
    }

    /** shard 为 null 表示"该租户不存在"，即负缓存条目。 */
    private record CacheEntry(TenantShard shard, long expiresAtMillis, long createdAtMillis) {

        boolean isExpired(long nowMillis) {
            return nowMillis >= expiresAtMillis;
        }
    }

    /** 缓存运行状况，用于判断容量与 TTL 是否配置合理。 */
    public record CacheStats(long hits, long misses, int size) {

        public double hitRate() {
            long total = hits + misses;
            return (total == 0) ? 0.0 : (double) hits / total;
        }
    }
}
