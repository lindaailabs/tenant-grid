package io.github.lindaailabs.tenantgrid.core.quota;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 令牌桶限流：稳态速率由 {@code permitsPerSecond} 控制，
 * 桶容量 {@code burstCapacity} 决定允许的瞬时突发。
 *
 * <p>惰性补充令牌——只在取用时按时间差计算，不需要后台线程。
 */
public final class TokenBucketRateLimiter implements RateLimiter {

    private final double permitsPerSecond;
    private final double burstCapacity;
    private final int maxTrackedTenants;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(double permitsPerSecond, double burstCapacity, int maxTrackedTenants) {
        if (permitsPerSecond <= 0) {
            throw new IllegalArgumentException("permitsPerSecond must be positive, got " + permitsPerSecond);
        }
        if (burstCapacity <= 0) {
            throw new IllegalArgumentException("burstCapacity must be positive, got " + burstCapacity);
        }
        if (maxTrackedTenants <= 0) {
            throw new IllegalArgumentException("maxTrackedTenants must be positive, got " + maxTrackedTenants);
        }
        this.permitsPerSecond = permitsPerSecond;
        this.burstCapacity = burstCapacity;
        this.maxTrackedTenants = maxTrackedTenants;
    }

    @Override
    public boolean tryAcquire(String tenantId) {
        if (tenantId == null) {
            return true;
        }
        Bucket bucket = bucketFor(tenantId);
        synchronized (bucket) {
            refill(bucket);
            if (bucket.tokens >= 1.0d) {
                bucket.tokens -= 1.0d;
                bucket.acquired++;
                return true;
            }
            bucket.rejected++;
            return false;
        }
    }

    @Override
    public double permitsPerSecond() {
        return permitsPerSecond;
    }

    /** 已跟踪的租户数，供容量观察。 */
    public int trackedTenants() {
        return buckets.size();
    }

    private void refill(Bucket bucket) {
        long now = System.nanoTime();
        double elapsedSeconds = (now - bucket.lastRefillNanos) / 1_000_000_000.0d;
        if (elapsedSeconds <= 0) {
            return;
        }
        bucket.tokens = Math.min(burstCapacity, bucket.tokens + elapsedSeconds * permitsPerSecond);
        bucket.lastRefillNanos = now;
    }

    private Bucket bucketFor(String tenantId) {
        Bucket bucket = buckets.computeIfAbsent(tenantId, id -> new Bucket(burstCapacity));
        if (buckets.size() > maxTrackedTenants) {
            evictStaleBuckets();
        }
        return bucket;
    }

    /** 只清理令牌已满的桶——它们对限流判定没有影响。 */
    private void evictStaleBuckets() {
        int excess = buckets.size() - maxTrackedTenants;
        if (excess <= 0) {
            return;
        }
        int removed = 0;
        for (Map.Entry<String, Bucket> entry : buckets.entrySet()) {
            if (removed >= excess) {
                break;
            }
            Bucket bucket = entry.getValue();
            synchronized (bucket) {
                refill(bucket);
                if (bucket.tokens >= burstCapacity - 0.5d && buckets.remove(entry.getKey(), bucket)) {
                    removed++;
                }
            }
        }
    }

    private static final class Bucket {

        private double tokens;
        private long lastRefillNanos;
        private long acquired;
        private long rejected;

        Bucket(double initialTokens) {
            this.tokens = initialTokens;
            this.lastRefillNanos = System.nanoTime();
        }
    }
}
