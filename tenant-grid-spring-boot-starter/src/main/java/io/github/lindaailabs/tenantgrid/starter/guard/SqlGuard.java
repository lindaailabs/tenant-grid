package io.github.lindaailabs.tenantgrid.starter.guard;

/**
 * SQL 校验器。
 *
 * <p>在 JDBC 层拦截每一条 SQL，检查是否携带租户列。
 * 这是共享库行级隔离的最后一道防线——路由只决定"落到哪个库"，
 * 同一库内多个租户的行级隔离，完全依赖 SQL 里的租户条件。
 */
@FunctionalInterface
public interface SqlGuard {

    /**
     * 校验一条 SQL。
     *
     * @param sql 待执行的 SQL
     * @throws io.github.lindaailabs.tenantgrid.core.exception.TenantIdMissingException
     *         ENFORCE 模式下 SQL 缺失租户列时抛出
     */
    void check(String sql);
}
