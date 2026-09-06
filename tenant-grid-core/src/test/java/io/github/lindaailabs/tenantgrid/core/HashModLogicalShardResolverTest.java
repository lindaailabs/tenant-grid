package io.github.lindaailabs.tenantgrid.core;

import io.github.lindaailabs.tenantgrid.core.exception.UnknownDataSourceException;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HashModLogicalShardResolverTest {

    private static final List<String> NODES = List.of("ds_a", "ds_b", "ds_c", "ds_d");

    @Test
    void alwaysResolvesToAConfiguredNode() {
        HashModLogicalShardResolver resolver =
                new HashModLogicalShardResolver(Map.of("std", NODES));

        for (int i = 0; i < 500; i++) {
            assertThat(NODES).contains(resolver.resolve("t_" + i, "std"));
        }
    }

    @Test
    void usesAllNodes() {
        HashModLogicalShardResolver resolver =
                new HashModLogicalShardResolver(Map.of("std", NODES));

        Set<String> used = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            used.add(resolver.resolve("t_" + i, "std"));
        }

        assertThat(used).containsExactlyInAnyOrderElementsOf(NODES);
    }

    @Test
    void stableAcrossInstances() {
        HashModLogicalShardResolver first = new HashModLogicalShardResolver(Map.of("std", NODES));
        HashModLogicalShardResolver second = new HashModLogicalShardResolver(Map.of("std", NODES));

        // 分片函数必须稳定：不同实例（乃至不同 JVM）对同一租户必须算出同一节点
        for (int i = 0; i < 100; i++) {
            String tenantId = "t_" + i;
            assertThat(first.resolve(tenantId, "std")).isEqualTo(second.resolve(tenantId, "std"));
        }
    }

    @Test
    void singleNodeGroupAlwaysResolvesToThatNode() {
        HashModLogicalShardResolver resolver =
                new HashModLogicalShardResolver(Map.of("solo", List.of("ds_only")));

        assertThat(resolver.resolve("t_1", "solo")).isEqualTo("ds_only");
        assertThat(resolver.resolve("t_2", "solo")).isEqualTo("ds_only");
    }

    @Test
    void rejectsUnknownGroup() {
        HashModLogicalShardResolver resolver =
                new HashModLogicalShardResolver(Map.of("std", NODES));

        assertThatThrownBy(() -> resolver.resolve("t_1", "nope"))
                .isInstanceOf(UnknownDataSourceException.class)
                .hasMessageContaining("nope")
                .hasMessageContaining("std");
    }

    @Test
    void rejectsEmptyGroup() {
        assertThatThrownBy(() -> new HashModLogicalShardResolver(Map.of("empty", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }
}
