package io.github.lindaailabs.tenantgrid.core.exception;

/**
 * 异常基类。全部为 {@link RuntimeException}，因为路由失败无法业务恢复，
 * 强制 catch 只会污染调用方。
 */
public class TenantGridException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TenantGridException(String message) {
        super(message);
    }

    public TenantGridException(String message, Throwable cause) {
        super(message, cause);
    }
}
