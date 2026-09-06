package io.github.lindaailabs.tenantgrid.core.migration;

import java.util.List;

/**
 * 迁移任务持久化（SPI）。
 *
 * <p>必须持久化：迁移可能跨小时甚至跨天，进程重启后要能接着推进或回滚。
 * 内存实现仅供测试。
 */
public interface MigrationStore {

    void save(MigrationTask task);

    /** 返回该租户的迁移任务；不存在返回 {@code null}。 */
    MigrationTask find(String tenantId);

    void remove(String tenantId);

    /** 全部任务，供管理台展示。 */
    List<MigrationTask> all();
}
