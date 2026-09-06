package io.github.lindaailabs.tenantgrid.core.datasource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

class DefaultDataSourceRegistryTest {

    private DefaultDataSourceRegistry registry;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    @BeforeEach
    void setUp() {
        registry = new DefaultDataSourceRegistry();
    }

    @AfterEach
    void tearDown() {
        registry.close();
        pool.shutdownNow();
    }

    private static DataSource closeableDataSource() {
        return mock(DataSource.class, withSettings().extraInterfaces(Closeable.class));
    }

    private static void verifyClosed(DataSource dataSource) throws IOException {
        verify((Closeable) dataSource).close();
    }

    private static void verifyNotClosed(DataSource dataSource) throws IOException {
        verify((Closeable) dataSource, never()).close();
    }

    @Test
    void registersAndLooksUpDatasource() {
        DataSource dataSource = closeableDataSource();

        registry.register("ds_0", dataSource);

        assertThat(registry.get("ds_0")).isSameAs(dataSource);
        assertThat(registry.names()).containsExactly("ds_0");
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.contains("ds_0")).isTrue();
    }

    @Test
    void seedsFromConstructorMap() {
        DataSource first = closeableDataSource();
        DataSource second = closeableDataSource();

        DefaultDataSourceRegistry seeded =
                new DefaultDataSourceRegistry(Map.of("ds_a", first, "ds_b", second), "ds_a");

        assertThat(seeded.get("ds_a")).isSameAs(first);
        assertThat(seeded.get("ds_b")).isSameAs(second);
        assertThat(seeded.defaultDataSource()).isSameAs(first);
        seeded.close();
    }

    @Test
    void replacingKeepsPreviousInstanceOpen() throws Exception {
        DataSource original = closeableDataSource();
        DataSource replacement = closeableDataSource();
        registry.register("ds_0", original);

        registry.register("ds_0", replacement);

        assertThat(registry.get("ds_0")).isSameAs(replacement);
        // 旧实例不能自动关闭：它可能仍被在途请求持有，关闭会造成连接中断
        verifyNotClosed(original);
    }

    @Test
    void unregisterStopsRoutingImmediately() throws Exception {
        DataSource dataSource = closeableDataSource();
        registry.register("ds_0", dataSource);

        DataSource removed = registry.unregister("ds_0");

        assertThat(removed).isSameAs(dataSource);
        assertThat(registry.get("ds_0")).isNull();
        verifyNotClosed(dataSource);
    }

    @Test
    void unregisterOfUnknownNameReturnsNull() {
        assertThat(registry.unregister("nope")).isNull();
        assertThat(registry.unregister(null)).isNull();
    }

    @Test
    void closesImmediatelyWithoutGracePeriod() throws Exception {
        DataSource dataSource = closeableDataSource();
        registry.register("ds_0", dataSource);

        registry.unregisterAndClose("ds_0", Duration.ZERO);

        assertThat(registry.get("ds_0")).isNull();
        verifyClosed(dataSource);
    }

    /** 核心场景：摘除立即切断新流量，宽限期结束后才真正关闭连接池。 */
    @Test
    void closesAfterGracePeriod() throws Exception {
        DataSource dataSource = closeableDataSource();
        CountDownLatch closed = new CountDownLatch(1);
        doAnswer(invocation -> {
            closed.countDown();
            return null;
        }).when((Closeable) dataSource).close();

        registry.register("ds_0", dataSource);
        registry.unregisterAndClose("ds_0", Duration.ofMillis(80));

        // 摘除瞬间即生效，但连接尚未关闭——在途请求还能正常跑完
        assertThat(registry.get("ds_0")).isNull();
        verifyNotClosed(dataSource);

        assertThat(closed.await(2, TimeUnit.SECONDS)).isTrue();
        verifyClosed(dataSource);
    }

    @Test
    void removingTheFallbackClearsIt() {
        DataSource dataSource = closeableDataSource();
        registry.register("ds_0", dataSource);
        registry.setDefault("ds_0");

        registry.unregister("ds_0");

        assertThat(registry.defaultDataSource()).isNull();
    }

    @Test
    void rejectsUnregisteredFallback() {
        assertThatThrownBy(() -> registry.setDefault("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void rejectsNullName() {
        assertThatThrownBy(() -> registry.register(null, closeableDataSource()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void closeShutsDownEveryDatasource() throws Exception {
        DataSource first = closeableDataSource();
        DataSource second = closeableDataSource();
        registry.register("ds_0", first);
        registry.register("ds_1", second);

        registry.close();

        assertThat(registry.size()).isZero();
        verifyClosed(first);
        verifyClosed(second);
    }

    @Test
    void supportsConcurrentRegistration() throws Exception {
        AtomicInteger sequence = new AtomicInteger();
        for (int i = 0; i < 8; i++) {
            pool.submit(() -> registry.register("ds_" + sequence.incrementAndGet(),
                    closeableDataSource()));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // 名称两两不同，并发注册不应丢失任何一个
        assertThat(registry.size()).isEqualTo(8);
    }
}
