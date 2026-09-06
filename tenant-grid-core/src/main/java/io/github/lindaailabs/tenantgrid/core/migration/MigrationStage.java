package io.github.lindaailabs.tenantgrid.core.migration;

/**
 * 租户迁移阶段。
 *
 * <p>完整的升级路径（长尾 → 大客户，共享库 → 独立库）：
 * <pre>
 * INIT → DUAL_WRITE → CATCH_UP → VERIFY → CUT_OVER → COMPLETED
 *                                                  ↘ ROLLED_BACK
 * </pre>
 *
 * <p>每一步都可以停在原地重试；任何一步失败都可以 {@code ROLLED_BACK}，
 * 因为切流之前源库始终是权威数据源。
 */
public enum MigrationStage {

    /** 已登记迁移计划，尚未开始。 */
    INIT,

    /** 双写：写入同时落到源库和目标库。 */
    DUAL_WRITE,

    /** 存量搬迁 + 增量追平。 */
    CATCH_UP,

    /** 一致性校验。 */
    VERIFY,

    /** 切读：把读流量逐步切到目标库。 */
    CUT_OVER,

    /** 迁移完成，停止双写。 */
    COMPLETED,

    /** 已回滚到源库。 */
    ROLLED_BACK,

    /** 校验未通过或搬迁失败。 */
    FAILED
}
