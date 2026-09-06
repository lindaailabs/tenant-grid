package io.github.lindaailabs.tenantgrid.core.exception;

/**
 * 当前调用链上没有租户上下文。
 *
 * <p>这是最容易被忽视、也最危险的一类错误：如果静默回落到默认库，
 * 会造成跨租户数据污染。因此 strict 模式下直接抛异常。
 */
public class MissingTenantContextException extends TenantGridException {

    private static final long serialVersionUID = 1L;

    public MissingTenantContextException() {
        super("No tenant bound to the current thread. "
                + "Tenant Grid runs in strict mode, so it refuses to fall back to a default "
                + "datasource (that would silently mix data across tenants). "
                + "Bind a tenant via TenantContext.set(..) / TenantContext.runAs(..), "
                + "or enable the web resolver.");
    }
}
