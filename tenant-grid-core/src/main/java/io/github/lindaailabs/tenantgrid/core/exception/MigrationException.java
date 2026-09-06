package io.github.lindaailabs.tenantgrid.core.exception;

/**
 * 迁移流程无法继续。
 *
 * <p>例如从 FAILED 状态继续推进、或对不存在的任务执行 advance。
 * 出现该异常时，正确的处置通常是 {@code rollback()}。
 */
public class MigrationException extends TenantGridException {

    private static final long serialVersionUID = 1L;

    private final String tenantId;

    public MigrationException(String tenantId, String message) {
        super("Migration of tenant '" + tenantId + "' cannot proceed: " + message);
        this.tenantId = tenantId;
    }

    public MigrationException(String tenantId, String message, Throwable cause) {
        super("Migration of tenant '" + tenantId + "' cannot proceed: " + message, cause);
        this.tenantId = tenantId;
    }

    public String getTenantId() {
        return tenantId;
    }
}
