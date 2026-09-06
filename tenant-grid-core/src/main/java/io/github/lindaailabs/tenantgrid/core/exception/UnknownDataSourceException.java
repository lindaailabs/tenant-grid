package io.github.lindaailabs.tenantgrid.core.exception;

/**
 * 逻辑分组未配置，或路由解析出的 dsKey 在已注册数据源中不存在。
 *
 * <p>典型原因：新增了共享库节点但忘了在 {@code logical-groups} 里声明，
 * 或元数据里的 dsKey 与实际数据源名称对不上。
 */
public class UnknownDataSourceException extends TenantGridException {

    private static final long serialVersionUID = 1L;

    public UnknownDataSourceException(String message) {
        super(message);
    }
}
