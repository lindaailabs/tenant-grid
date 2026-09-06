package io.github.lindaailabs.tenantgrid.core.datasource;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Set;

/**
 * 数据源注册表——动态热插拔的入口。
 *
 * <p>关键契约：{@link #unregister(String)} 会**立即**让该数据源不再接受新流量，
 * 但不会立刻关闭它。这样已经在途的请求仍能正常归还连接，避免"拔网线式"中断。
 *
 * <pre>{@code
 * registry.register("ds_vip_7", buildVipDataSource());   // 新大客户入驻，热加库
 * registry.unregisterAndClose("ds_std_old", Duration.ofSeconds(30));  // 摘除旧库，宽限 30s
 * }</pre>
 */
public interface DataSourceRegistry {

    /**
     * 注册（或覆盖）一个数据源。
     *
     * <p>若同名数据源已存在，新实例立即生效，但**旧实例不会被自动关闭**——
     * 它可能正被在途请求使用。需要优雅下线请先调用 {@link #unregisterAndClose}。
     */
    void register(String name, DataSource dataSource);

    /**
     * 立即摘除数据源，使其不再接受新流量。
     *
     * @return 被摘除的数据源；原本不存在则返回 {@code null}。调用方负责关闭它。
     */
    DataSource unregister(String name);

    /**
     * 摘除数据源，并在宽限期结束后关闭它。
     *
     * <p>这是热下线的推荐方式：摘除瞬间即切断新流量，宽限期保证在途请求能跑完，
     * 之后再真正关闭连接池。
     */
    void unregisterAndClose(String name, Duration gracePeriod);

    /** 按名称查找，未注册返回 {@code null}。 */
    DataSource get(String name);

    /** 设置无租户上下文（非严格模式）时回落到的数据源。 */
    void setDefault(String name);

    /** 回落数据源，未设置返回 {@code null}。 */
    DataSource defaultDataSource();

    /** 当前已注册的数据源名称。 */
    Set<String> names();

    /** 已注册数量。 */
    int size();

    /** 是否已注册该名称。 */
    boolean contains(String name);
}
