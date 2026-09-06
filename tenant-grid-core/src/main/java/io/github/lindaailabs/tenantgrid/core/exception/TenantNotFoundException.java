package io.github.lindaailabs.tenantgrid.core.exception;

/**
 * 租户在元数据中不存在。通常意味着新租户尚未初始化分片映射，
 * 或配置中心数据缺失——属于需要人工介入的配置错误。
 */
public class TenantNotFoundException extends TenantGridException {

    private static final long serialVersionUID = 1L;

    private final String tenantId;

    public TenantNotFoundException(String tenantId) {
        super("No shard metadata found for tenant '" + tenantId
                + "'. Initialize its tenant_shard record before routing requests to it.");
        this.tenantId = tenantId;
    }

    public String getTenantId() {
        return tenantId;
    }
}
