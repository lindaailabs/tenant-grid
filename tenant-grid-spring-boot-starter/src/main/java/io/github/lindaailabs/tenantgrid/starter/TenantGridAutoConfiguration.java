package io.github.lindaailabs.tenantgrid.starter;

import io.github.lindaailabs.tenantgrid.core.CachingMetadataProvider;
import io.github.lindaailabs.tenantgrid.core.HashModLogicalShardResolver;
import io.github.lindaailabs.tenantgrid.core.InMemoryMetadataProvider;
import io.github.lindaailabs.tenantgrid.core.LogicalShardResolver;
import io.github.lindaailabs.tenantgrid.core.MetadataProvider;
import io.github.lindaailabs.tenantgrid.core.ShardType;
import io.github.lindaailabs.tenantgrid.core.TenantRouter;
import io.github.lindaailabs.tenantgrid.core.TenantShard;
import io.github.lindaailabs.tenantgrid.core.datasource.DataSourceRegistry;
import io.github.lindaailabs.tenantgrid.core.datasource.DefaultDataSourceRegistry;
import io.github.lindaailabs.tenantgrid.core.migration.MigrationCoordinator;
import io.github.lindaailabs.tenantgrid.core.quota.DegradationConfig;
import io.github.lindaailabs.tenantgrid.core.quota.QuotaGuard;
import io.github.lindaailabs.tenantgrid.core.quota.RateLimiter;
import io.github.lindaailabs.tenantgrid.core.quota.TenantQuotaGuard;
import io.github.lindaailabs.tenantgrid.core.quota.TokenBucketRateLimiter;
import io.github.lindaailabs.tenantgrid.starter.actuate.TenantGridEndpoint;
import io.github.lindaailabs.tenantgrid.starter.guard.GuardSet;
import io.github.lindaailabs.tenantgrid.starter.guard.JSqlParserSqlGuard;
import io.github.lindaailabs.tenantgrid.starter.guard.SqlGuard;
import io.github.lindaailabs.tenantgrid.starter.guard.SqlGuardMode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tenant Grid 自动装配。
 *
 * <p>路由三件套（{@link MetadataProvider} / {@link LogicalShardResolver} / {@link TenantRouter}）
 * 总是注册，业务方可以用自己的 Bean 覆盖任意一项。
 *
 * <p>{@link DataSource} 与 {@link DataSourceRegistry} 只在配置了
 * {@code tenant-grid.datasources} 时才接管——很多项目已有自己的数据源装配，不该被抢。
 */
@AutoConfiguration
@ConditionalOnClass({AbstractRoutingDataSource.class, DataSource.class})
@EnableConfigurationProperties(TenantGridProperties.class)
public class TenantGridAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LogicalShardResolver tenantGridLogicalShardResolver(TenantGridProperties properties) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        properties.getLogicalGroups().forEach((name, group) -> groups.put(name, group.getNodes()));
        return new HashModLogicalShardResolver(groups);
    }

    /**
     * 默认元数据实现（来自配置文件），默认套上本地缓存。
     *
     * <p>开启缓存时实际类型是 {@link CachingMetadataProvider}，
     * 业务方可注入该类型调用 {@code invalidate(tenantId)}，在租户迁移后立即失效缓存。
     *
     * <p>若业务方自己提供了 {@link MetadataProvider}（接配置中心 / 数据库），
     * 本方法会整体退让，缓存需由业务方自行套用 {@link CachingMetadataProvider}。
     */
    @Bean
    @ConditionalOnMissingBean
    public MetadataProvider tenantGridMetadataProvider(TenantGridProperties properties) {
        Map<String, TenantShard> shards = new LinkedHashMap<>();
        properties.getTenants().forEach((tenantId, spec) -> {
            TenantShard shard = (spec.getShardType() == ShardType.PHYSICAL)
                    ? TenantShard.physical(tenantId, spec.getDsKey())
                    : TenantShard.logical(tenantId, spec.getLogicalGroup());
            shards.put(tenantId, shard);
        });

        TenantGridProperties.MetadataCacheSpec cacheSpec = properties.getMetadataCache();
        MetadataProvider delegate = new InMemoryMetadataProvider(shards);
        if (!cacheSpec.isEnabled()) {
            return delegate;
        }
        return new CachingMetadataProvider(delegate, cacheSpec.getTtl(),
                cacheSpec.getNegativeTtl(), cacheSpec.getMaxSize());
    }

    @Bean
    @ConditionalOnMissingBean
    public TenantRouter tenantGridTenantRouter(MetadataProvider metadataProvider,
                                               LogicalShardResolver logicalShardResolver) {
        return new TenantRouter(metadataProvider, logicalShardResolver);
    }

    @Configuration(proxyBeanMethods = false)
    @Conditional(OnDatasourcesConfigured.class)
    static class RoutingDataSourceConfiguration {

        /**
         * 注册表即热插拔入口：注入它即可在运行时增删数据源，无需重启。
         *
         * <p>{@code destroyMethod} 保证容器关闭时释放所有连接池。
         */
        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean(DataSourceRegistry.class)
        public DefaultDataSourceRegistry tenantGridDataSourceRegistry(TenantGridProperties properties) {
            Map<String, DataSource> initial = new LinkedHashMap<>();
            properties.getDatasources()
                    .forEach((name, spec) -> initial.put(name, buildDataSource(spec)));

            DefaultDataSourceRegistry registry =
                    new DefaultDataSourceRegistry(initial, properties.getDefaultDatasource());
            return registry;
        }

        /**
         * SQL 校验器。JSqlParser 是可选依赖——只有真正开启了校验才需要它，
         * 不发放行，避免给只用路由功能的使用者强塞一个 SQL 解析器。
         */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnClass(name = "net.sf.jsqlparser.parser.CCJSqlParserUtil")
        public SqlGuard tenantGridSqlGuard(TenantGridProperties properties) {
            TenantGridProperties.SqlGuardSpec spec = properties.getSqlGuard();
            return new JSqlParserSqlGuard(
                    spec.getTenantColumn(), spec.getMode(), spec.getExemptTables());
        }

        /** 配额守卫，无外部依赖，默认启用。同时承载单条 SQL 的执行超时。 */
        @Bean
        @ConditionalOnMissingBean
        public QuotaGuard tenantGridQuotaGuard(TenantGridProperties properties) {
            TenantGridProperties.QuotaSpec spec = properties.getQuota();
            return new TenantQuotaGuard(spec.getPermitsPerTenant(),
                    spec.getSlowHoldThreshold().toMillis(), spec.getMaxTrackedTenants(),
                    buildDegradation(properties.getDegradation()),
                    (int) spec.getStatementTimeout().toSeconds(),
                    (int) spec.getMinStatementTimeout().toSeconds());
        }

        /** 限流器，默认关闭——QPS 上限与部署容量强相关。 */
        @Bean
        @ConditionalOnMissingBean
        public RateLimiter tenantGridRateLimiter(TenantGridProperties properties) {
            TenantGridProperties.RateLimitSpec spec = properties.getRateLimit();
            return new TokenBucketRateLimiter(spec.getPermitsPerSecond(),
                    spec.getBurstCapacity(), spec.getMaxTrackedTenants());
        }

        @Bean
        @ConditionalOnMissingBean(DataSource.class)
        public DataSource tenantGridDataSource(DataSourceRegistry registry,
                                               TenantRouter router,
                                               TenantGridProperties properties,
                                               ObjectProvider<SqlGuard> sqlGuardProvider,
                                               ObjectProvider<QuotaGuard> quotaGuardProvider,
                                               ObjectProvider<RateLimiter> rateLimiterProvider) {
            SqlGuard sqlGuard = resolveSqlGuard(properties, sqlGuardProvider);
            QuotaGuard quotaGuard = properties.getQuota().isEnabled()
                    ? quotaGuardProvider.getIfAvailable()
                    : null;
            RateLimiter rateLimiter = properties.getRateLimit().isEnabled()
                    ? rateLimiterProvider.getIfAvailable()
                    : null;

            return new RoutingDataSource(router, properties.isStrict(), registry,
                    GuardSet.of(sqlGuard, quotaGuard, rateLimiter));
        }

        private DegradationConfig buildDegradation(TenantGridProperties.DegradationSpec spec) {
            if (!spec.isEnabled()) {
                return DegradationConfig.DISABLED;
            }
            return DegradationConfig.enabled(spec.getEvaluationInterval().toMillis(),
                    spec.getMinPermits(), spec.getRejectionRatioThreshold(),
                    spec.getRecoveryAfterCleanEvaluations());
        }

        private SqlGuard resolveSqlGuard(TenantGridProperties properties,
                                         ObjectProvider<SqlGuard> sqlGuardProvider) {
            TenantGridProperties.SqlGuardSpec spec = properties.getSqlGuard();
            if (spec.getMode() == SqlGuardMode.OFF) {
                return null;
            }
            SqlGuard sqlGuard = sqlGuardProvider.getIfAvailable();
            if (sqlGuard == null) {
                // 静默降级成"不校验"会让使用者误以为防护生效了，必须显式报错
                throw new IllegalStateException(
                        "tenant-grid.sql-guard.mode=" + spec.getMode()
                                + " requires JSqlParser on the classpath. "
                                + "Add dependency: com.github.jsqlparser:jsqlparser:5.3");
            }
            return sqlGuard;
        }

        private DataSource buildDataSource(TenantGridProperties.DataSourceSpec spec) {
            DataSourceBuilder<?> builder = DataSourceBuilder.create();
            builder.url(spec.getUrl());
            builder.username(spec.getUsername());
            builder.password(spec.getPassword());
            if (spec.getDriverClassName() != null) {
                builder.driverClassName(spec.getDriverClassName());
            }
            return builder.build();
        }
    }

    /**
     * Actuator 端点。actuator 是 provided 依赖，未引入时整段跳过。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
    static class TenantGridActuatorConfiguration {

        /**
         * 只要 Actuator 在 classpath 上就注册。
         *
         * <p>是否<b>暴露</b>到 HTTP 由 {@code management.endpoints.web.exposure.include}
         * 控制，与 Bean 是否存在无关，因此这里不必用
         * {@code @ConditionalOnAvailableEndpoint}——它依赖 web/cloudfoundry 的
         * 暴露判定器，在非 web 上下文里会误判为不可用。
         */
        @Bean
        @ConditionalOnMissingBean
        public TenantGridEndpoint tenantGridEndpoint(
                ObjectProvider<DataSourceRegistry> registry,
                ObjectProvider<QuotaGuard> quotaGuard,
                ObjectProvider<RateLimiter> rateLimiter,
                ObjectProvider<CachingMetadataProvider> metadataCache,
                ObjectProvider<MigrationCoordinator> migrationCoordinator) {
            return new TenantGridEndpoint(
                    registry.getIfAvailable(),
                    quotaGuard.getIfAvailable(),
                    rateLimiter.getIfAvailable(),
                    metadataCache.getIfAvailable(),
                    migrationCoordinator.getIfAvailable());
        }
    }

    /** 仅当 tenant-grid.datasources 非空时装配数据源。 */
    static class OnDatasourcesConfigured implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            TenantGridProperties properties = Binder.get(context.getEnvironment())
                    .bind("tenant-grid", TenantGridProperties.class)
                    .orElse(null);
            return properties != null && !properties.getDatasources().isEmpty();
        }
    }
}
