package io.github.lindaailabs.tenantgrid.core.quota;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 按租户分配并发许可、记录用量，并支持自动降级。
 *
 * <p>配额用<b>可调计数器</b>而不是 {@link java.util.concurrent.Semaphore}：
 * 信号量的 {@code reducePermits} 是 protected 方法，外部无法缩小，
 * 而降级需要在运行时把某个租户的配额压下来。
 *
 * <p>无界风险：租户 churn 会让 slot 只增不减，因此超过
 * {@code maxTrackedTenants} 时会清理空闲（无进行中请求）的 slot。
 */
public final class TenantQuotaGuard implements QuotaGuard {

    /** 惩罚上限：再降也没意义，会被 minPermits 兜住。 */
    private static final int MAX_PENALTY_LEVEL = 4;

    private final int permitsPerTenant;
    private final long slowHoldThresholdMillis;
    private final int maxTrackedTenants;
    private final DegradationConfig degradation;
    private final int statementTimeoutSeconds;
    private final int minStatementTimeoutSeconds;

    private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();

    public TenantQuotaGuard(int permitsPerTenant, long slowHoldThresholdMillis, int maxTrackedTenants) {
        this(permitsPerTenant, slowHoldThresholdMillis, maxTrackedTenants, DegradationConfig.DISABLED);
    }

    public TenantQuotaGuard(int permitsPerTenant,
                            long slowHoldThresholdMillis,
                            int maxTrackedTenants,
                            DegradationConfig degradation) {
        this(permitsPerTenant, slowHoldThresholdMillis, maxTrackedTenants, degradation, 0, 1);
    }

    /**
     * @param statementTimeoutSeconds    单条 SQL 的执行超时（秒）；{@code 0} 表示不限制
     * @param minStatementTimeoutSeconds 降级后能压到的下限；实际生效值不低于 1 秒，
     *                                   因为 0 在 JDBC 里代表"不限时"
     */
    public TenantQuotaGuard(int permitsPerTenant,
                            long slowHoldThresholdMillis,
                            int maxTrackedTenants,
                            DegradationConfig degradation,
                            int statementTimeoutSeconds,
                            int minStatementTimeoutSeconds) {
        if (permitsPerTenant <= 0) {
            throw new IllegalArgumentException("permitsPerTenant must be positive, got " + permitsPerTenant);
        }
        if (maxTrackedTenants <= 0) {
            throw new IllegalArgumentException("maxTrackedTenants must be positive, got " + maxTrackedTenants);
        }
        if (statementTimeoutSeconds < 0) {
            throw new IllegalArgumentException(
                    "statementTimeoutSeconds must not be negative, got " + statementTimeoutSeconds);
        }
        if (minStatementTimeoutSeconds < 0) {
            throw new IllegalArgumentException(
                    "minStatementTimeoutSeconds must not be negative, got " + minStatementTimeoutSeconds);
        }
        this.permitsPerTenant = permitsPerTenant;
        this.slowHoldThresholdMillis = slowHoldThresholdMillis;
        this.maxTrackedTenants = maxTrackedTenants;
        this.degradation = degradation;
        this.statementTimeoutSeconds = statementTimeoutSeconds;
        this.minStatementTimeoutSeconds = minStatementTimeoutSeconds;
    }

    @Override
    public boolean tryAcquire(String tenantId) {
        if (tenantId == null) {
            return true;
        }
        Slot slot = slotFor(tenantId);
        maybeEvaluate(slot);

        boolean acquired = slot.tryAcquire();
        if (acquired) {
            slot.acquired.incrementAndGet();
        } else {
            slot.rejected.incrementAndGet();
        }
        return acquired;
    }

    @Override
    public void release(String tenantId) {
        if (tenantId == null) {
            return;
        }
        Slot slot = slots.get(tenantId);
        if (slot != null) {
            slot.release();
        }
    }

    @Override
    public void recordHoldTime(String tenantId, long heldMillis) {
        if (tenantId == null) {
            return;
        }
        Slot slot = slots.get(tenantId);
        if (slot == null) {
            return;
        }
        slot.maxHoldMillis.accumulateAndGet(heldMillis, Math::max);
        if (heldMillis > slowHoldThresholdMillis) {
            slot.slowHolds.incrementAndGet();
        }
    }

    @Override
    public int permitsPerTenant() {
        return permitsPerTenant;
    }

    @Override
    public int effectivePermits(String tenantId) {
        Slot slot = (tenantId == null) ? null : slots.get(tenantId);
        return (slot == null) ? permitsPerTenant : slot.permits;
    }

    /**
     * 超时与配额共用同一套惩罚档位：降级一级，超时也砍一半。
     *
     * <p>只对已经表现出问题的租户收紧，正常租户始终拿到基准超时。
     * 结果不低于 1 秒——JDBC 里 0 表示不限时，降级到 0 等于解除限制，与意图相反。
     */
    @Override
    public int effectiveTimeoutSeconds(String tenantId) {
        if (statementTimeoutSeconds <= 0) {
            return 0;
        }
        Slot slot = (tenantId == null) ? null : slots.get(tenantId);
        int penalty = (slot == null) ? 0 : slot.penaltyLevel;
        return Math.max(Math.max(1, minStatementTimeoutSeconds), statementTimeoutSeconds >> penalty);
    }

    @Override
    public List<TenantUsage> usage() {
        List<TenantUsage> snapshot = new ArrayList<>(slots.size());
        for (Map.Entry<String, Slot> entry : slots.entrySet()) {
            Slot slot = entry.getValue();
            int timeoutSeconds = effectiveTimeoutSeconds(entry.getKey());
            snapshot.add(new TenantUsage(
                    entry.getKey(),
                    slot.permits,
                    slot.active.get(),
                    slot.acquired.get(),
                    slot.rejected.get(),
                    slot.slowHolds.get(),
                    slot.maxHoldMillis.get(),
                    slot.penaltyLevel,
                    timeoutSeconds));
        }
        return snapshot;
    }

    private void maybeEvaluate(Slot slot) {
        if (!degradation.enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (slot) {
            if (now - slot.lastEvaluatedMillis < degradation.evaluationIntervalMillis()) {
                return;
            }
            evaluate(slot, now);
        }
    }

    private void evaluate(Slot slot, long now) {
        long acquiredTotal = slot.acquired.get();
        long rejectedTotal = slot.rejected.get();
        long acquiredDelta = acquiredTotal - slot.acquiredAtLastEval;
        long rejectedDelta = rejectedTotal - slot.rejectedAtLastEval;

        slot.acquiredAtLastEval = acquiredTotal;
        slot.rejectedAtLastEval = rejectedTotal;
        slot.lastEvaluatedMillis = now;

        long total = acquiredDelta + rejectedDelta;
        if (total <= 0) {
            return;   // 该窗口内没有流量，不调整
        }

        double rejectionRatio = (double) rejectedDelta / total;
        if (rejectionRatio > degradation.rejectionRatioThreshold()) {
            slot.penaltyLevel = Math.min(MAX_PENALTY_LEVEL, slot.penaltyLevel + 1);
            slot.cleanEvaluations = 0;
        } else if (rejectedDelta == 0) {
            slot.cleanEvaluations++;
            if (slot.cleanEvaluations >= degradation.recoveryAfterCleanEvaluations()) {
                slot.penaltyLevel = Math.max(0, slot.penaltyLevel - 1);
                slot.cleanEvaluations = 0;
            }
        }
        applyPenalty(slot);
    }

    private void applyPenalty(Slot slot) {
        int shrunk = permitsPerTenant >> slot.penaltyLevel;
        slot.permits = Math.max(degradation.minPermits(), (shrunk <= 0) ? 1 : shrunk);
    }

    private Slot slotFor(String tenantId) {
        Slot slot = slots.computeIfAbsent(tenantId, id -> new Slot(permitsPerTenant));
        if (slots.size() > maxTrackedTenants) {
            evictIdleSlots();
        }
        return slot;
    }

    /** 只清理没有进行中请求的 slot，避免影响活跃租户。 */
    private void evictIdleSlots() {
        int excess = slots.size() - maxTrackedTenants;
        if (excess <= 0) {
            return;
        }
        int removed = 0;
        for (var entry : slots.entrySet()) {
            if (removed >= excess) {
                break;
            }
            Slot slot = entry.getValue();
            if (slot.active.get() == 0 && slots.remove(entry.getKey(), slot)) {
                removed++;
            }
        }
    }

    private static final class Slot {

        private final AtomicInteger active = new AtomicInteger();
        private final AtomicLong acquired = new AtomicLong();
        private final AtomicLong rejected = new AtomicLong();
        private final AtomicLong slowHolds = new AtomicLong();
        private final AtomicLong maxHoldMillis = new AtomicLong();

        private volatile int permits;

        private long lastEvaluatedMillis = System.currentTimeMillis();
        private long acquiredAtLastEval;
        private long rejectedAtLastEval;
        private int penaltyLevel;
        private int cleanEvaluations;

        Slot(int permits) {
            this.permits = permits;
        }

        boolean tryAcquire() {
            while (true) {
                int current = active.get();
                if (current >= permits) {
                    return false;
                }
                if (active.compareAndSet(current, current + 1)) {
                    return true;
                }
            }
        }

        void release() {
            active.decrementAndGet();
        }
    }
}
