package io.github.lindaailabs.tenantgrid.starter.actuate;

import io.github.lindaailabs.tenantgrid.core.CachingMetadataProvider;
import io.github.lindaailabs.tenantgrid.core.datasource.DataSourceRegistry;
import io.github.lindaailabs.tenantgrid.core.migration.MigrationCoordinator;
import io.github.lindaailabs.tenantgrid.core.migration.MigrationTask;
import io.github.lindaailabs.tenantgrid.core.quota.QuotaGuard;
import io.github.lindaailabs.tenantgrid.core.quota.RateLimiter;
import io.github.lindaailabs.tenantgrid.core.quota.TenantUsage;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.ArrayList;
import java.util.List;

/**
 * Actuator 端点：聚合 Tenant Grid 的运行状态。
 *
 * <p>所有依赖都是可选的——没启用对应功能（或没装对应依赖）时，
 * 相关字段为 {@code null} 或空列表，而不是让整个端点起不来。
 *
 * <p>暴露需配置：{@code management.endpoints.web.exposure.include=tenantgrid}
 */
@Endpoint(id = "tenantgrid")
public class TenantGridEndpoint {

    private final DataSourceRegistry registry;
    private final QuotaGuard quotaGuard;
    private final RateLimiter rateLimiter;
    private final CachingMetadataProvider metadataCache;
    private final MigrationCoordinator migrationCoordinator;

    public TenantGridEndpoint(DataSourceRegistry registry,
                              QuotaGuard quotaGuard,
                              RateLimiter rateLimiter,
                              CachingMetadataProvider metadataCache,
                              MigrationCoordinator migrationCoordinator) {
        this.registry = registry;
        this.quotaGuard = quotaGuard;
        this.rateLimiter = rateLimiter;
        this.metadataCache = metadataCache;
        this.migrationCoordinator = migrationCoordinator;
    }

    @ReadOperation
    public TenantGridReport report() {
        return new TenantGridReport(
                registry == null ? List.of() : new ArrayList<>(registry.names()),
                quotaGuard == null ? List.of() : quotaGuard.usage(),
                rateLimiter == null ? null : rateLimiter.permitsPerSecond(),
                metadataCache == null ? null : metadataCache.stats(),
                migrationCoordinator == null ? List.of() : migrationCoordinator.activeTasks());
    }

    public record TenantGridReport(
            List<String> datasources,
            List<TenantUsage> tenantUsage,
            Double rateLimitPermitsPerSecond,
            CachingMetadataProvider.CacheStats metadataCache,
            List<MigrationTask> activeMigrations) {
    }
}
