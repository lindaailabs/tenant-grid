package io.github.lindaailabs.tenantgrid.starter;

import io.github.lindaailabs.tenantgrid.core.HashModLogicalShardResolver;
import io.github.lindaailabs.tenantgrid.core.InMemoryMetadataProvider;
import io.github.lindaailabs.tenantgrid.core.TenantContext;
import io.github.lindaailabs.tenantgrid.core.TenantRouter;
import io.github.lindaailabs.tenantgrid.core.TenantShard;
import io.github.lindaailabs.tenantgrid.core.datasource.DefaultDataSourceRegistry;
import io.github.lindaailabs.tenantgrid.core.exception.MissingTenantContextException;
import io.github.lindaailabs.tenantgrid.core.exception.TenantNotFoundException;
import io.github.lindaailabs.tenantgrid.core.exception.TenantQuotaExceededException;
import io.github.lindaailabs.tenantgrid.core.exception.TenantRateLimitExceededException;
import io.github.lindaailabs.tenantgrid.core.exception.UnknownDataSourceException;
import io.github.lindaailabs.tenantgrid.core.quota.DegradationConfig;
import io.github.lindaailabs.tenantgrid.core.quota.RateLimiter;
import io.github.lindaailabs.tenantgrid.core.quota.TenantQuotaGuard;
import io.github.lindaailabs.tenantgrid.core.quota.TokenBucketRateLimiter;
import io.github.lindaailabs.tenantgrid.starter.guard.GuardSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoutingDataSourceTest {

    private final DataSource vipDs = mock(DataSource.class);
    private final DataSource stdDs0 = mock(DataSource.class);
    private final DataSource stdDs1 = mock(DataSource.class);

    private DefaultDataSourceRegistry registry;
    private TenantRouter router;

    @BeforeEach
    void setUp() throws Exception {
        registry = new DefaultDataSourceRegistry();
        registry.register("ds_vip_1", vipDs);
        registry.register("ds_std_0", stdDs0);
        registry.register("ds_std_1", stdDs1);

        when(vipDs.getConnection()).thenReturn(mock(Connection.class));
        when(stdDs0.getConnection()).thenReturn(mock(Connection.class));
        when(stdDs1.getConnection()).thenReturn(mock(Connection.class));

        InMemoryMetadataProvider metadata = new InMemoryMetadataProvider();
        metadata.register(TenantShard.physical("vip_1", "ds_vip_1"));
        metadata.register(TenantShard.logical("t_1", "std"));
        // vip_new 的独立库尚未注册，用于验证热加入
        metadata.register(TenantShard.physical("vip_new", "ds_vip_new"));

        router = new TenantRouter(metadata, new HashModLogicalShardResolver(
                Map.of("std", List.of("ds_std_0", "ds_std_1"))));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        registry.close();
    }

    private RoutingDataSource routing(boolean strict, boolean withFallback) {
        return routing(strict, withFallback, GuardSet.NONE);
    }

    private RoutingDataSource routing(boolean strict, boolean withFallback, GuardSet guards) {
        if (withFallback) {
            registry.setDefault("ds_std_0");
        }
        RoutingDataSource dataSource = new RoutingDataSource(router, strict, registry, guards);
        dataSource.afterPropertiesSet();
        return dataSource;
    }

    @Test
    void routesPhysicalTenantToItsDedicatedDataSource() {
        RoutingDataSource dataSource = routing(true, false);

        DataSource resolved = TenantContext.runAs("vip_1", dataSource::determineTargetDataSource);

        assertThat(resolved).isSameAs(vipDs);
    }

    @Test
    void routesLogicalTenantToASharedNode() {
        RoutingDataSource dataSource = routing(true, false);

        DataSource resolved = TenantContext.runAs("t_1", dataSource::determineTargetDataSource);

        assertThat(resolved).isSameAs(registry.get(router.route("t_1").dsKey()));
        assertThat(resolved).isIn(stdDs0, stdDs1);
    }

    @Test
    void refusesToFallbackWithoutTenantInStrictMode() {
        RoutingDataSource dataSource = routing(true, true);

        // 即便配了 fallback，strict 模式下也不允许静默回落——那会造成跨租户污染
        assertThatThrownBy(dataSource::determineTargetDataSource)
                .isInstanceOf(MissingTenantContextException.class);
    }

    @Test
    void fallsBackToDefaultDataSourceWhenNotStrict() {
        RoutingDataSource dataSource = routing(false, true);

        assertThat(dataSource.determineTargetDataSource()).isSameAs(stdDs0);
    }

    @Test
    void failsWhenTenantIsUnknown() {
        RoutingDataSource dataSource = routing(true, false);

        assertThatThrownBy(() -> TenantContext.runAs("ghost", dataSource::determineTargetDataSource))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void failsClearlyWhenTargetDatasourceIsNotRegistered() {
        RoutingDataSource dataSource = routing(true, false);

        assertThatThrownBy(() -> TenantContext.runAs("vip_new", dataSource::determineTargetDataSource))
                .isInstanceOf(UnknownDataSourceException.class)
                .hasMessageContaining("ds_vip_new");
    }

    /** 热加入：新大客户的独立库上线后无需重启，下一次路由即命中。 */
    @Test
    void picksUpDatasourceRegisteredAfterStartup() {
        RoutingDataSource dataSource = routing(true, false);
        DataSource hotAdded = mock(DataSource.class);

        registry.register("ds_vip_new", hotAdded);

        assertThat(TenantContext.runAs("vip_new", dataSource::determineTargetDataSource))
                .isSameAs(hotAdded);
    }

    /** 热下线：摘除后立即不再路由到该库。 */
    @Test
    void stopsRoutingToUnregisteredDatasource() {
        RoutingDataSource dataSource = routing(true, false);
        assertThat(TenantContext.runAs("vip_1", dataSource::determineTargetDataSource)).isSameAs(vipDs);

        registry.unregister("ds_vip_1");

        assertThatThrownBy(() -> TenantContext.runAs("vip_1", dataSource::determineTargetDataSource))
                .isInstanceOf(UnknownDataSourceException.class);
    }

    @Test
    void resolvesIndependentlyForEachTenant() {
        RoutingDataSource dataSource = routing(true, false);

        assertThat(TenantContext.runAs("vip_1", dataSource::determineTargetDataSource)).isSameAs(vipDs);
        assertThat(TenantContext.runAs("t_1", dataSource::determineTargetDataSource)).isIn(stdDs0, stdDs1);
        assertThat(TenantContext.runAs("vip_1", dataSource::determineTargetDataSource)).isSameAs(vipDs);
    }

    @Test
    void rejectsTenantThatExceedsItsQuota() throws Exception {
        TenantQuotaGuard quota = new TenantQuotaGuard(1, 10_000L, 100);
        RoutingDataSource dataSource = routing(true, false, GuardSet.of(null, quota, null));

        TenantContext.runAs("vip_1", () -> {
            try {
                dataSource.getConnection();          // 占满该租户的配额
                assertThatThrownBy(dataSource::getConnection)
                        .isInstanceOf(TenantQuotaExceededException.class);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }

    /**
     * 被配额拒绝的那条连接必须还回池里。
     * 否则配额保护本身就会漏光连接池——比不做配额更糟。
     */
    @Test
    void returnsConnectionToPoolWhenQuotaRejectsIt() throws Exception {
        TenantQuotaGuard quota = new TenantQuotaGuard(1, 10_000L, 100);
        Connection raw = mock(Connection.class);
        when(vipDs.getConnection()).thenReturn(raw);

        RoutingDataSource dataSource = routing(true, false, GuardSet.of(null, quota, null));
        TenantContext.runAs("vip_1", () -> {
            try {
                dataSource.getConnection();
                assertThatThrownBy(dataSource::getConnection)
                        .isInstanceOf(TenantQuotaExceededException.class);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });

        verify(raw).close();
    }

    @Test
    void quotaIsEnforcedPerTenant() throws Exception {
        TenantQuotaGuard quota = new TenantQuotaGuard(1, 10_000L, 100);
        RoutingDataSource dataSource = routing(true, false, GuardSet.of(null, quota, null));

        TenantContext.runAs("vip_1", () -> {
            try {
                dataSource.getConnection();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });

        // vip_1 已占满配额，同库其他租户不受影响
        TenantContext.runAs("t_1", () -> {
            try {
                assertThat(dataSource.getConnection()).isNotNull();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }

    /**
     * 超时取自配额守卫（会随降级收紧），因此不依赖 SQL 校验是否开启——
     * 没有 sqlGuard 时也必须织入。
     */
    @Test
    void appliesStatementTimeoutFromQuotaGuard() throws Exception {
        TenantQuotaGuard quota =
                new TenantQuotaGuard(4, 10_000L, 100, DegradationConfig.DISABLED, 30, 5);
        Connection raw = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(vipDs.getConnection()).thenReturn(raw);
        when(raw.prepareStatement(anyString())).thenReturn(statement);

        RoutingDataSource dataSource = routing(true, false, GuardSet.of(null, quota, null));
        TenantContext.runAs("vip_1", () -> {
            try {
                dataSource.getConnection().prepareStatement("SELECT 1");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });

        verify(statement).setQueryTimeout(30);
    }

    /** 没配超时时不能覆盖驱动 / 连接池自己的设置。 */
    @Test
    void leavesTimeoutAloneWhenNotConfigured() throws Exception {
        TenantQuotaGuard quota = new TenantQuotaGuard(4, 10_000L, 100);
        Connection raw = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(vipDs.getConnection()).thenReturn(raw);
        when(raw.prepareStatement(anyString())).thenReturn(statement);

        RoutingDataSource dataSource = routing(true, false, GuardSet.of(null, quota, null));
        TenantContext.runAs("vip_1", () -> {
            try {
                dataSource.getConnection().prepareStatement("SELECT 1");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });

        verify(statement, never()).setQueryTimeout(anyInt());
    }

    @Test
    void withoutGuardsConnectionsAreUnlimited() throws Exception {
        RoutingDataSource dataSource = routing(true, false);

        TenantContext.runAs("vip_1", () -> {
            try {
                for (int i = 0; i < 20; i++) {
                    dataSource.getConnection();
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }

    @Test
    void rejectsTenantThatExceedsItsRateLimit() throws Exception {
        // 桶容量 1：第一次取走令牌后，第二次必然被挡，无需依赖真实耗时
        RateLimiter rateLimiter = new TokenBucketRateLimiter(1.0d, 1.0d, 100);
        RoutingDataSource dataSource =
                routing(true, false, GuardSet.of(null, null, rateLimiter));

        TenantContext.runAs("vip_1", () -> {
            try {
                dataSource.getConnection();
                assertThatThrownBy(dataSource::getConnection)
                        .isInstanceOf(TenantRateLimitExceededException.class);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }

    @Test
    void rateLimitIsAppliedPerTenant() throws Exception {
        RateLimiter rateLimiter = new TokenBucketRateLimiter(1.0d, 1.0d, 100);
        RoutingDataSource dataSource =
                routing(true, false, GuardSet.of(null, null, rateLimiter));

        TenantContext.runAs("vip_1", () -> {
            try {
                dataSource.getConnection();   // 用掉 vip_1 的令牌
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });

        TenantContext.runAs("t_1", () -> {
            try {
                assertThat(dataSource.getConnection()).isNotNull();   // 其他租户不受影响
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }

    /** 被限流拒绝的连接同样要还回池里，否则限流会漏光连接池。 */
    @Test
    void returnsConnectionToPoolWhenRateLimitRejectsIt() throws Exception {
        RateLimiter rateLimiter = new TokenBucketRateLimiter(1.0d, 1.0d, 100);
        Connection raw = mock(Connection.class);
        when(vipDs.getConnection()).thenReturn(raw);

        RoutingDataSource dataSource =
                routing(true, false, GuardSet.of(null, null, rateLimiter));
        TenantContext.runAs("vip_1", () -> {
            try {
                dataSource.getConnection();
                assertThatThrownBy(dataSource::getConnection)
                        .isInstanceOf(TenantRateLimitExceededException.class);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });

        verify(raw).close();
    }
}
