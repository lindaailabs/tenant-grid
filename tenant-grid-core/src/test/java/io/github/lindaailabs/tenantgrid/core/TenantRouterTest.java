package io.github.lindaailabs.tenantgrid.core;

import io.github.lindaailabs.tenantgrid.core.exception.MissingTenantContextException;
import io.github.lindaailabs.tenantgrid.core.exception.TenantNotFoundException;
import io.github.lindaailabs.tenantgrid.core.exception.UnknownDataSourceException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantRouterTest {

    private static final List<String> STD_NODES = List.of("ds_std_0", "ds_std_1", "ds_std_2");

    private TenantRouter routerWith(String... tenants) {
        InMemoryMetadataProvider metadata = new InMemoryMetadataProvider();
        for (String tenantId : tenants) {
            // 约定：以 vip 开头的走独立库，其余走共享库组
            metadata.register(tenantId.startsWith("vip")
                    ? TenantShard.physical(tenantId, "ds_" + tenantId)
                    : TenantShard.logical(tenantId, "std"));
        }
        LogicalShardResolver resolver = new HashModLogicalShardResolver(Map.of("std", STD_NODES));
        return new TenantRouter(metadata, resolver);
    }

    @Test
    void routesPhysicalTenantToItsDedicatedDatabase() {
        ShardPlan plan = routerWith("vip_1").route("vip_1");

        assertThat(plan.dsKey()).isEqualTo("ds_vip_1");
        assertThat(plan.shardType()).isEqualTo(ShardType.PHYSICAL);
        assertThat(plan.status()).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    void routesLogicalTenantToANodeInSharedGroup() {
        ShardPlan plan = routerWith("t_001").route("t_001");

        assertThat(plan.shardType()).isEqualTo(ShardType.LOGICAL);
        assertThat(STD_NODES).contains(plan.dsKey());
    }

    @Test
    void routingIsDeterministic() {
        TenantRouter router = routerWith("t_001");

        assertThat(router.route("t_001").dsKey())
                .isEqualTo(router.route("t_001").dsKey());
    }

    @Test
    void spreadsLogicalTenantsAcrossNodes() {
        TenantRouter router = routerWith("t_1", "t_2", "t_3", "t_4", "t_5", "t_6", "t_7", "t_8");

        for (String tenantId : List.of("t_1", "t_2", "t_3", "t_4", "t_5", "t_6", "t_7", "t_8")) {
            assertThat(STD_NODES).contains(router.route(tenantId).dsKey());
        }
    }

    @Test
    void routesCurrentTenantFromContext() {
        TenantRouter router = routerWith("vip_9");

        String dsKey = TenantContext.runAs("vip_9", () -> router.routeCurrent().dsKey());

        assertThat(dsKey).isEqualTo("ds_vip_9");
        assertThat(TenantContext.currentId()).isNull();
    }

    @Test
    void rejectsUnknownTenant() {
        TenantRouter router = routerWith("vip_1");

        assertThatThrownBy(() -> router.route("ghost"))
                .isInstanceOf(TenantNotFoundException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void rejectsBlankTenant() {
        TenantRouter router = routerWith("vip_1");

        assertThatThrownBy(() -> router.route("  "))
                .isInstanceOf(MissingTenantContextException.class);
    }

    @Test
    void rejectsUnknownLogicalGroup() {
        InMemoryMetadataProvider metadata = new InMemoryMetadataProvider();
        metadata.register(TenantShard.logical("t_001", "nonexistent"));
        TenantRouter router = new TenantRouter(metadata,
                new HashModLogicalShardResolver(Map.of("std", STD_NODES)));

        assertThatThrownBy(() -> router.route("t_001"))
                .isInstanceOf(UnknownDataSourceException.class)
                .hasMessageContaining("nonexistent");
    }

    @Test
    void physicalShardRequiresDsKey() {
        assertThatThrownBy(() -> TenantShard.physical("vip_1", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dsKey");
    }

    @Test
    void logicalShardRequiresGroup() {
        assertThatThrownBy(() -> TenantShard.logical("t_001", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("logicalGroup");
    }
}
