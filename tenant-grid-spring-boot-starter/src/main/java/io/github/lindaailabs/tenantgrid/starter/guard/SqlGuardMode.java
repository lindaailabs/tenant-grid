package io.github.lindaailabs.tenantgrid.starter.guard;

/**
 * SQL 校验器的执行模式。
 *
 * <p>默认 {@link #OFF}：存量系统接入时不应因为历史 SQL 不合规而直接起不来，
 * 应先开 {@link #WARN} 观察一段时间，清理干净后再切 {@link #ENFORCE}。
 */
public enum SqlGuardMode {

    /** 不校验。 */
    OFF,

    /** 记录告警但放行，用于灰度摸底。 */
    WARN,

    /** 直接抛 {@link io.github.lindaailabs.tenantgrid.core.exception.TenantIdMissingException} 阻断。 */
    ENFORCE
}
