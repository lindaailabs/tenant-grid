package io.github.lindaailabs.tenantgrid.starter;

import io.github.lindaailabs.tenantgrid.core.CachingMetadataProvider;
import io.github.lindaailabs.tenantgrid.core.InMemoryMetadataProvider;
import io.github.lindaailabs.tenantgrid.core.LogicalShardResolver;
import io.github.lindaailabs.tenantgrid.core.MetadataProvider;
import io.github.lindaailabs.tenantgrid.core.ShardType;
import io.github.lindaailabs.tenantgrid.core.TenantContext;
import io.github.lindaailabs.tenantgrid.core.TenantRouter;
import io.github.lindaailabs.tenantgrid.core.TenantShard;
import io.github.lindaailabs.tenantgrid.core.datasource.DataSourceRegistry;
import io.github.lindaailabs.tenantgrid.core.exception.UnknownDataSourceException;
import io.github.lindaailabs.tenantgrid.core.quota.QuotaGuard;
import io.github.lindaailabs.tenantgrid.core.quota.RateLimiter;
import io.github.lindaailabs.tenantgrid.starter.actuate.TenantGridEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TenantGridAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TenantGridAutoConfiguration.class));

    private final ApplicationContextRunner actuatorRunner = contextRunner;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void registersRoutingBeansByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(TenantRouter.class);
            assertThat(context).hasSingleBean(MetadataProvider.class);
            assertThat(context).hasSingleBean(LogicalShardResolver.class);
        });
    }

    @Test
    void doesNotTakeOverDataSourceWhenNoneConfigured() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(DataSource.class);
            assertThat(context).doesNotHaveBean(DataSourceRegistry.class);
        });
    }

    @Test
    void strictModeIsOnByDefault() {
        contextRunner.run(context ->
                assertThat(context.getBean(TenantGridProperties.class).isStrict()).isTrue());
    }

    @Test
    void strictModeCanBeDisabled() {
        contextRunner
                .withPropertyValues("tenant-grid.strict=false")
                .run(context ->
                        assertThat(context.getBean(TenantGridProperties.class).isStrict()).isFalse());
    }

    @Test
    void bindsTenantShardMetadataFromProperties() {
        contextRunner
                .withPropertyValues(
                        "tenant-grid.logical-groups.std.nodes[0]=ds_std_0",
                        "tenant-grid.logical-groups.std.nodes[1]=ds_std_1",
                        "tenant-grid.tenants.vip_1.shard-type=physical",
                        "tenant-grid.tenants.vip_1.ds-key=ds_vip_1",
                        "tenant-grid.tenants.t_001.shard-type=logical",
                        "tenant-grid.tenants.t_001.logical-group=std")
                .run(context -> {
                    TenantRouter router = context.getBean(TenantRouter.class);

                    assertThat(router.route("vip_1").dsKey()).isEqualTo("ds_vip_1");
                    assertThat(router.route("vip_1").shardType()).isEqualTo(ShardType.PHYSICAL);

                    assertThat(router.route("t_001").shardType()).isEqualTo(ShardType.LOGICAL);
                    assertThat(router.route("t_001").dsKey()).isIn("ds_std_0", "ds_std_1");
                });
    }

    @Test
    void exposesRegistryAlongsideRoutingDataSource() {
        contextRunner
                .withPropertyValues(
                        "tenant-grid.datasources.ds_a.url=jdbc:h2:mem:ds_a",
                        "tenant-grid.datasources.ds_b.url=jdbc:h2:mem:ds_b")
                .run(context -> {
                    assertThat(context).hasSingleBean(DataSourceRegistry.class);
                    assertThat(context.getBean(DataSource.class)).isInstanceOf(RoutingDataSource.class);
                    assertThat(context.getBean(DataSourceRegistry.class).names())
                            .containsExactlyInAnyOrder("ds_a", "ds_b");
                });
    }

    /** 热插拔闭环：启动后注册的数据源立刻可被路由命中。 */
    @Test
    void dynamicallyRegisteredDatasourceIsImmediatelyRoutable() {
        contextRunner
                .withPropertyValues(
                        "tenant-grid.datasources.ds_a.url=jdbc:h2:mem:ds_a",
                        "tenant-grid.tenants.vip_new.shard-type=physical",
                        "tenant-grid.tenants.vip_new.ds-key=ds_vip_new")
                .run(context -> {
                    DataSourceRegistry registry = context.getBean(DataSourceRegistry.class);
                    RoutingDataSource routing = (RoutingDataSource) context.getBean(DataSource.class);

                    // 独立库尚未就绪 → 明确报错，而不是悄悄落到别处
                    assertThatThrownBy(() ->
                            TenantContext.runAs("vip_new", routing::determineTargetDataSource))
                            .isInstanceOf(UnknownDataSourceException.class);

                    DataSource hotAdded = mock(DataSource.class);
                    registry.register("ds_vip_new", hotAdded);

                    assertThat(TenantContext.runAs("vip_new", routing::determineTargetDataSource))
                            .isSameAs(hotAdded);
                });
    }

    @Test
    void failsFastWhenFallbackDatasourceIsNotDeclared() {
        contextRunner
                .withPropertyValues(
                        "tenant-grid.datasources.ds_a.url=jdbc:h2:mem:ds_a",
                        "tenant-grid.default-datasource=ds_missing")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void wrapsDefaultMetadataProviderInCache() {
        contextRunner
                .withPropertyValues(
                        "tenant-grid.logical-groups.std.nodes[0]=ds_std_0",
                        "tenant-grid.tenants.t_1.shard-type=logical",
                        "tenant-grid.tenants.t_1.logical-group=std")
                .run(context -> assertThat(context.getBean(MetadataProvider.class))
                        .isInstanceOf(CachingMetadataProvider.class));
    }

    @Test
    void bindsCacheSettings() {
        contextRunner
                .withPropertyValues(
                        "tenant-grid.metadata-cache.ttl=2m",
                        "tenant-grid.metadata-cache.negative-ttl=1s",
                        "tenant-grid.metadata-cache.max-size=500")
                .run(context -> {
                    TenantGridProperties.MetadataCacheSpec spec =
                            context.getBean(TenantGridProperties.class).getMetadataCache();

                    assertThat(spec.isEnabled()).isTrue();
                    assertThat(spec.getTtl()).isEqualTo(Duration.ofMinutes(2));
                    assertThat(spec.getNegativeTtl()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(spec.getMaxSize()).isEqualTo(500);
                });
    }

    @Test
    void cacheCanBeDisabled() {
        contextRunner
                .withPropertyValues("tenant-grid.metadata-cache.enabled=false")
                .run(context -> assertThat(context.getBean(MetadataProvider.class))
                        .isNotInstanceOf(CachingMetadataProvider.class)
                        .isInstanceOf(InMemoryMetadataProvider.class));
    }

    @Test
    void registersQuotaGuardAndBindsSettings() {
        contextRunner
                .withPropertyValues(
                        "tenant-grid.datasources.ds_a.url=jdbc:h2:mem:ds_a",
                        "tenant-grid.quota.permits-per-tenant=7",
                        "tenant-grid.quota.slow-hold-threshold=250ms",
                        "tenant-grid.quota.max-tracked-tenants=999")
                .run(context -> {
                    assertThat(context).hasSingleBean(QuotaGuard.class);
                    assertThat(context.getBean(QuotaGuard.class).permitsPerTenant()).isEqualTo(7);

                    TenantGridProperties.QuotaSpec spec =
                            context.getBean(TenantGridProperties.class).getQuota();
                    assertThat(spec.isEnabled()).isTrue();
                    assertThat(spec.getSlowHoldThreshold()).isEqualTo(Duration.ofMillis(250));
                    assertThat(spec.getMaxTrackedTenants()).isEqualTo(999);
                });
    }

    @Test
    void quotaIsEnabledByDefault() {
        contextRunner
                .withPropertyValues("tenant-grid.datasources.ds_a.url=jdbc:h2:mem:ds_a")
                .run(context -> assertThat(context.getBean(TenantGridProperties.class)
                        .getQuota().isEnabled()).isTrue());
    }

    /** 限流默认关闭：QPS 上限与部署容量强相关，默认值很容易给错。 */
    @Test
    void rateLimitIsOffByDefault() {
        contextRunner
                .withPropertyValues("tenant-grid.datasources.ds_a.url=jdbc:h2:mem:ds_a")
                .run(context -> assertThat(context.getBean(TenantGridProperties.class)
                        .getRateLimit().isEnabled()).isFalse());
    }

    @Test
    void bindsRateLimitSettings() {
        contextRunner
                .withPropertyValues(
                        "tenant-grid.datasources.ds_a.url=jdbc:h2:mem:ds_a",
                        "tenant-grid.rate-limit.enabled=true",
                        "tenant-grid.rate-limit.permits-per-second=250",
                        "tenant-grid.rate-limit.burst-capacity=80")
                .run(context -> {
                    assertThat(context).hasSingleBean(RateLimiter.class);
                    assertThat(context.getBean(RateLimiter.class).permitsPerSecond()).isEqualTo(250.0d);
                });
    }

    /** 降级默认开启：它只在租户真被拒绝时才生效，属于兜底而非前置限制。 */
    @Test
    void degradationIsEnabledByDefault() {
        contextRunner
                .withPropertyValues("tenant-grid.datasources.ds_a.url=jdbc:h2:mem:ds_a")
                .run(context -> assertThat(context.getBean(TenantGridProperties.class)
                        .getDegradation().isEnabled()).isTrue());
    }

    @Test
    void bindsDegradationSettings() {
        contextRunner
                .withPropertyValues(
                        "tenant-grid.datasources.ds_a.url=jdbc:h2:mem:ds_a",
                        "tenant-grid.degradation.min-permits=3",
                        "tenant-grid.degradation.evaluation-interval=45s",
                        "tenant-grid.degradation.rejection-ratio-threshold=0.35")
                .run(context -> {
                    TenantGridProperties.DegradationSpec spec =
                            context.getBean(TenantGridProperties.class).getDegradation();
                    assertThat(spec.getMinPermits()).isEqualTo(3);
                    assertThat(spec.getEvaluationInterval()).isEqualTo(Duration.ofSeconds(45));
                    assertThat(spec.getRejectionRatioThreshold()).isEqualTo(0.35d);
                });
    }

    @Test
    void exposesActuatorEndpoint() {
        actuatorRunner
                .withPropertyValues("tenant-grid.datasources.ds_a.url=jdbc:h2:mem:ds_a")
                .run(context -> assertThat(context).hasSingleBean(TenantGridEndpoint.class));
    }

    @Test
    void endpointReportsRegisteredDatasources() {
        actuatorRunner
                .withPropertyValues(
                        "tenant-grid.datasources.ds_a.url=jdbc:h2:mem:ds_a",
                        "tenant-grid.datasources.ds_b.url=jdbc:h2:mem:ds_b")
                .run(context -> {
                    TenantGridEndpoint.TenantGridReport report =
                            context.getBean(TenantGridEndpoint.class).report();

                    assertThat(report.datasources()).containsExactlyInAnyOrder("ds_a", "ds_b");
                    assertThat(report.activeMigrations()).isEmpty();
                    assertThat(report.tenantUsage()).isEmpty();
                });
    }

    @Test
    void endpointSurvivesMissingOptionalComponents() {
        // 未配置数据源时注册表也不存在，端点仍需可用而不是抛异常
        actuatorRunner.run(context -> {
            TenantGridEndpoint.TenantGridReport report =
                    context.getBean(TenantGridEndpoint.class).report();

            assertThat(report.datasources()).isEmpty();
            assertThat(report.rateLimitPermitsPerSecond()).isNull();
        });
    }

    @Test
    void backsOffWhenUserDefinesOwnMetadataProvider() {
        contextRunner
                .withUserConfiguration(CustomMetadataProviderConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(MetadataProvider.class);
                    assertThat(context.getBean(MetadataProvider.class))
                            .isInstanceOf(CustomMetadataProvider.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomMetadataProviderConfiguration {

        @Bean
        MetadataProvider customMetadataProvider() {
            return new CustomMetadataProvider();
        }
    }

    static class CustomMetadataProvider implements MetadataProvider {

        @Override
        public TenantShard find(String tenantId) {
            return TenantShard.physical(tenantId, "ds_custom");
        }
    }
}
