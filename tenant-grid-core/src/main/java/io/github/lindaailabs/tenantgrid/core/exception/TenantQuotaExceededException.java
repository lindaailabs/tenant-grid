package io.github.lindaailabs.tenantgrid.core.exception;

/**
 * 租户并发超出配额。
 *
 * <p>这是<b>保护性失败</b>：宁可让超限的那一个租户快速失败，
 * 也不能让它把共享连接池耗干，导致同库所有租户一起不可用。
 */
public class TenantQuotaExceededException extends TenantGridException {

    private static final long serialVersionUID = 1L;

    private final String tenantId;
    private final int limit;

    public TenantQuotaExceededException(String tenantId, int limit) {
        super("Tenant '" + tenantId + "' exceeded its concurrency quota of " + limit
                + " connections. This limit protects other tenants sharing the same database; "
                + "either the tenant is misbehaving or the quota needs raising.");
        this.tenantId = tenantId;
        this.limit = limit;
    }

    public String getTenantId() {
        return tenantId;
    }

    public int getLimit() {
        return limit;
    }
}
