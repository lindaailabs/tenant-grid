package io.github.lindaailabs.tenantgrid.core;

import io.github.lindaailabs.tenantgrid.core.exception.MissingTenantContextException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantContextTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(1);

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        executor.shutdownNow();
    }

    /** Supplier lambda 里不能抛受检异常，统一转成非受检。 */
    private static <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting task", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Task failed", e);
        }
    }

    @Test
    void bindsAndUnbindsTenant() {
        TenantContext.set("t_1");
        assertThat(TenantContext.currentId()).isEqualTo("t_1");

        TenantContext.clear();
        assertThat(TenantContext.currentId()).isNull();
    }

    @Test
    void blankTenantIsTreatedAsUnbound() {
        TenantContext.set("   ");
        assertThat(TenantContext.currentId()).isNull();
    }

    @Test
    void requireThrowsWhenUnbound() {
        assertThatThrownBy(TenantContext::require)
                .isInstanceOf(MissingTenantContextException.class);
    }

    @Test
    void runAsRestoresPreviousTenant() {
        TenantContext.set("outer");

        TenantContext.runAs("inner",
                () -> assertThat(TenantContext.currentId()).isEqualTo("inner"));

        assertThat(TenantContext.currentId()).isEqualTo("outer");
    }

    @Test
    void runAsClearsContextWhenThereWasNone() {
        TenantContext.runAs("inner",
                () -> assertThat(TenantContext.currentId()).isEqualTo("inner"));

        assertThat(TenantContext.currentId()).isNull();
    }

    @Test
    void runAsReturnsSupplierResult() {
        String result = TenantContext.runAs("t_1", () -> "value:" + TenantContext.currentId());

        assertThat(result).isEqualTo("value:t_1");
    }

    /**
     * 核心场景：业务代码把任务丢进线程池时，租户上下文不能丢。
     *
     * <p>这里先预热线程池——池中线程在绑定上下文之前就已存在，
     * 因此普通 InheritableThreadLocal 拿不到值，只有 TTL 包装可以。
     */
    @Test
    void propagatesTenantIntoPreWarmedThreadPool() throws Exception {
        executor.submit(() -> {}).get();

        String seenInPool = TenantContext.runAs("t_pool",
                () -> await(executor.submit(TenantContext.wrap(TenantContext::currentId))));

        assertThat(seenInPool).isEqualTo("t_pool");
    }

    @Test
    void doesNotReachThreadPoolWithoutWrapping() throws Exception {
        executor.submit(() -> {}).get();

        // 不包装就拿不到——这正是必须调用 TenantContext.wrap(..) 的原因
        String seenInPool = TenantContext.runAs("t_pool",
                () -> await(executor.submit(TenantContext::currentId)));

        assertThat(seenInPool).isNull();
    }

    @Test
    void propagatesThroughNestedSubmission() throws Exception {
        ExecutorService inner = Executors.newFixedThreadPool(1);
        inner.submit(() -> {}).get();
        try {
            String seen = TenantContext.runAs("t_deep", () -> await(executor.submit(
                    TenantContext.wrap(() -> await(inner.submit(TenantContext.wrap(TenantContext::currentId)))))));

            assertThat(seen).isEqualTo("t_deep");
        } finally {
            inner.shutdownNow();
        }
    }

    @Test
    void doesNotLeakBetweenIndependentTasks() throws Exception {
        executor.submit(() -> {}).get();

        String first = TenantContext.runAs("t_a",
                () -> await(executor.submit(TenantContext.wrap(TenantContext::currentId))));
        String second = TenantContext.runAs("t_b",
                () -> await(executor.submit(TenantContext.wrap(TenantContext::currentId))));

        assertThat(first).isEqualTo("t_a");
        assertThat(second).isEqualTo("t_b");
    }
}
