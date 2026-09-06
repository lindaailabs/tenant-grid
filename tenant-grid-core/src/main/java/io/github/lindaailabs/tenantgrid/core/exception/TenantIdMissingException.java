package io.github.lindaailabs.tenantgrid.core.exception;

/**
 * SQL 未携带租户列。
 *
 * <p>这是共享库场景下最危险的一类错误：带租户列的 SQL 缺失时，
 * 查询会跨越所有租户，轻则性能雪崩，重则数据泄露。
 * 因此在 ENFORCE 模式下直接阻断，而不是静默放行。
 */
public class TenantIdMissingException extends TenantGridException {

    private static final long serialVersionUID = 1L;

    private final String sql;

    public TenantIdMissingException(String message, String sql) {
        super(message);
        this.sql = sql;
    }

    /** 被拒绝的 SQL，便于定位到具体代码。 */
    public String getSql() {
        return sql;
    }
}
