package io.github.lindaailabs.tenantgrid.core.datasource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.Closeable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 线程安全的默认实现。
 *
 * <p>热下线的三步：先从 map 摘除（切断新流量）→ 宽限期内在途请求跑完 → 关闭连接池。
 * 顺序不能颠倒，否则会出现"新请求还在进来、连接已被关掉"的报错。
 */
public class DefaultDataSourceRegistry implements DataSourceRegistry, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DefaultDataSourceRegistry.class);

    private final ConcurrentHashMap<String, DataSource> sources = new ConcurrentHashMap<>();
    private final ScheduledExecutorService closer;

    private volatile DataSource fallback;

    public DefaultDataSourceRegistry() {
        this(Map.of(), null);
    }

    public DefaultDataSourceRegistry(Map<String, DataSource> initial, String defaultName) {
        this.closer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "tenant-grid-ds-closer");
            thread.setDaemon(true);
            return thread;
        });

        if (initial != null) {
            initial.forEach(this::register);
        }
        if (defaultName != null) {
            setDefault(defaultName);
        }
    }

    @Override
    public void register(String name, DataSource dataSource) {
        Objects.requireNonNull(name, "datasource name must not be null");
        Objects.requireNonNull(dataSource, "dataSource must not be null");

        DataSource previous = sources.put(name, dataSource);
        if (previous != null && previous != dataSource) {
            log.warn("Datasource '{}' was replaced in place. The previous instance is left open "
                    + "because in-flight requests may still hold its connections. "
                    + "Unregister and close it explicitly if it is truly retired.", name);
        }
        log.info("Registered datasource '{}', total={}", name, sources.size());
    }

    @Override
    public DataSource unregister(String name) {
        if (name == null) {
            return null;
        }
        DataSource removed = sources.remove(name);
        if (removed != null && removed == fallback) {
            fallback = null;
            log.warn("Datasource '{}' was the fallback target and has been removed; "
                    + "non-strict routing will now fail until a new default is set.", name);
        }
        return removed;
    }

    @Override
    public void unregisterAndClose(String name, Duration gracePeriod) {
        DataSource removed = unregister(name);
        if (removed == null) {
            return;
        }
        if (gracePeriod == null || gracePeriod.isZero() || gracePeriod.isNegative()) {
            closeQuietly(name, removed);
            return;
        }
        log.info("Datasource '{}' removed from routing; closing after {}ms grace period",
                name, gracePeriod.toMillis());
        closer.schedule(() -> closeQuietly(name, removed),
                gracePeriod.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public DataSource get(String name) {
        return (name == null) ? null : sources.get(name);
    }

    @Override
    public void setDefault(String name) {
        if (name == null) {
            fallback = null;
            return;
        }
        DataSource candidate = sources.get(name);
        if (candidate == null) {
            throw new IllegalArgumentException(
                    "Cannot use unregistered datasource '" + name + "' as fallback. Registered: " + names());
        }
        fallback = candidate;
    }

    @Override
    public DataSource defaultDataSource() {
        return fallback;
    }

    @Override
    public Set<String> names() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(sources.keySet()));
    }

    @Override
    public int size() {
        return sources.size();
    }

    @Override
    public boolean contains(String name) {
        return name != null && sources.containsKey(name);
    }

    /** 立即关闭所有数据源，用于容器关闭等场景（不做宽限等待）。 */
    @Override
    public void close() {
        closer.shutdownNow();
        for (String name : new ArrayList<>(sources.keySet())) {
            DataSource removed = unregister(name);
            if (removed != null) {
                closeQuietly(name, removed);
            }
        }
        fallback = null;
    }

    private void closeQuietly(String name, DataSource dataSource) {
        softEvict(name, dataSource);
        if (dataSource instanceof Closeable closeable) {
            try {
                closeable.close();
                log.info("Closed datasource '{}'", name);
            } catch (Exception e) {
                log.warn("Failed to close datasource '{}'", name, e);
            }
        }
    }

    /**
     * 通知连接池尽快回收空闲连接，让在途连接用完后自然退出。
     *
     * <p>目前只有 HikariCP 提供这个能力。用反射调用是为了不在 core 里引入 HikariCP 依赖——
     * 使用方完全可能用 Tomcat JDBC / DBCP2 等其他连接池，那些实现直接跳过即可。
     */
    private void softEvict(String name, DataSource dataSource) {
        try {
            Object poolBean = dataSource.getClass()
                    .getMethod("getHikariPoolMXBean")
                    .invoke(dataSource);
            if (poolBean != null) {
                poolBean.getClass().getMethod("softEvictConnections").invoke(poolBean);
            }
        } catch (NoSuchMethodException ignored) {
            // 非 Hikari 数据源，无此能力，直接走 close
        } catch (Exception e) {
            log.debug("Could not soft-evict connections of datasource '{}': {}", name, e.toString());
        }
    }
}
