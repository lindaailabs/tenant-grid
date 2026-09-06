package io.github.lindaailabs.tenantgrid.core.exception;

/**
 * 租户请求速率超上限。
 *
 * <p>与 {@link TenantQuotaExceededException} 的区别：配额管<b>并发数</b>（占着不放），
 * 限流管<b>速率</b>（来得太多）。两者都是保护性失败，只影响超限的那一个租户。
 */
public class TenantRateLimitExceededException extends TenantGridException {

    private static final long serialVersionUID = 1L;

    private final String tenantId;

    public TenantRateLimitExceededException(String tenantId, double permitsPerSecond) {
        super("Tenant '" + tenantId + "' exceeded its rate limit of " + permitsPerSecond
                + " requests/second. Back off and retry; the limit protects other tenants "
                + "sharing the same database.");
        this.tenantId = tenantId;
    }

    public String getTenantId() {
        return tenantId;
    }
}
