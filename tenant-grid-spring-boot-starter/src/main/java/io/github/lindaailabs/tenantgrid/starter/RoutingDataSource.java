package io.github.lindaailabs.tenantgrid.starter;

import io.github.lindaailabs.tenantgrid.core.TenantContext;
import io.github.lindaailabs.tenantgrid.core.TenantRouter;
import io.github.lindaailabs.tenantgrid.core.datasource.DataSourceRegistry;
import io.github.lindaailabs.tenantgrid.core.exception.MissingTenantContextException;
import io.github.lindaailabs.tenantgrid.core.exception.TenantQuotaExceededException;
import io.github.lindaailabs.tenantgrid.core.exception.TenantRateLimitExceededException;
import io.github.lindaailabs.tenantgrid.core.exception.UnknownDataSourceException;
import io.github.lindaailabs.tenantgrid.starter.guard.GuardedJdbc;
import io.github.lindaailabs.tenantgrid.starter.guard.GuardSet;
import io.github.lindaailabs.tenantgrid.starter.guard.QuotaAwareJdbc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/**
 * 按当前租户选择数据源。
 *
 * <p><b>为什么重写 {@link #determineTargetDataSource()}</b>：
 * 父类在 {@code afterPropertiesSet()} 时把 {@code targetDataSources} 快照进一张私有 map，
 * 之后注册的新数据源它永远看不到——这与热插拔直接冲突。
 * 因此这里让父类持有一个空壳，真正的查找走 {@link DataSourceRegistry}，它支持运行时增减。
 *
 * <p>这里只做「库级路由」：一次请求固定落在单个库，不做跨库归并，
 * 所以不需要 ShardingSphere 的 SQL 解析能力。
 */
public class RoutingDataSource extends AbstractRoutingDataSource {

    private static final Logger log = LoggerFactory.getLogger(RoutingDataSource.class);

    private final TenantRouter router;
    private final boolean strict;
    private final DataSourceRegistry registry;
    private final GuardSet guards;

    public RoutingDataSource(TenantRouter router, boolean strict, DataSourceRegistry registry) {
        this(router, strict, registry, GuardSet.NONE);
    }

    public RoutingDataSource(TenantRouter router,
                             boolean strict,
                             DataSourceRegistry registry,
                             GuardSet guards) {
        this.router = router;
        this.strict = strict;
        this.registry = registry;
        this.guards = guards;
        // 空壳：真实查找在 determineTargetDataSource() 中走 registry
        setTargetDataSources(Map.of());
    }

    @Override
    protected Object determineCurrentLookupKey() {
        String tenantId = TenantContext.currentId();
        if (tenantId == null) {
            if (strict) {
                throw new MissingTenantContextException();
            }
            return null;
        }
        return router.route(tenantId).dsKey();
    }

    @Override
    protected DataSource determineTargetDataSource() {
        Object lookupKey = determineCurrentLookupKey();
        DataSource dataSource = (lookupKey == null)
                ? registry.defaultDataSource()
                : registry.get(String.valueOf(lookupKey));

        if (dataSource == null) {
            throw new UnknownDataSourceException(
                    "No datasource registered for key '" + lookupKey + "'. Registered: " + registry.names());
        }
        return dataSource;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return protect(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return protect(super.getConnection(username, password));
    }

    /**
     * 防护织入顺序：限流 → 配额 → SQL 校验与执行超时。
     *
     * <p>限流放在最前是因为它最廉价：已经要被速率挡掉的请求，
     * 没必要再占一次配额、跑一次 SQL 解析。
     *
     * <p>配额代理在内层、SQL 代理在外层，这样 {@code close()} 从最外层一路穿透，
     * 许可必定被归还。超时只是给 Statement 设个值，不涉及代理层次。
     */
    private Connection protect(Connection connection) {
        String tenantId = TenantContext.currentId();
        Connection result = connection;

        if (guards.rateLimiter() != null && tenantId != null
                && !guards.rateLimiter().tryAcquire(tenantId)) {
            closeQuietly(connection);
            throw new TenantRateLimitExceededException(tenantId, guards.rateLimiter().permitsPerSecond());
        }

        if (guards.quotaGuard() != null && tenantId != null) {
            if (!guards.quotaGuard().tryAcquire(tenantId)) {
                // 连接已经拿到了，必须还回去，否则防护本身会漏光连接池
                closeQuietly(connection);
                throw new TenantQuotaExceededException(
                        tenantId, guards.quotaGuard().effectivePermits(tenantId));
            }
            result = QuotaAwareJdbc.wrap(result, guards.quotaGuard(), tenantId);
        }

        // 超时按租户取值：被降级的租户超时更短，让慢查询主动让位而不是一直占着连接
        int timeoutSeconds = (guards.quotaGuard() == null || tenantId == null)
                ? 0
                : guards.quotaGuard().effectiveTimeoutSeconds(tenantId);

        if (guards.sqlGuard() == null && timeoutSeconds <= 0) {
            return result;
        }
        return GuardedJdbc.wrapConnection(result, guards.sqlGuard(), timeoutSeconds);
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            log.debug("Failed to return a connection rejected by a guard", e);
        }
    }
}
