package io.github.lindaailabs.tenantgrid.core.migration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 内存实现，仅供测试。生产需换成数据库实现——迁移可能跨小时甚至跨天。 */
public final class InMemoryMigrationStore implements MigrationStore {

    private final Map<String, MigrationTask> tasks = new ConcurrentHashMap<>();

    @Override
    public void save(MigrationTask task) {
        tasks.put(task.tenantId(), task);
    }

    @Override
    public MigrationTask find(String tenantId) {
        return (tenantId == null) ? null : tasks.get(tenantId);
    }

    @Override
    public void remove(String tenantId) {
        tasks.remove(tenantId);
    }

    @Override
    public List<MigrationTask> all() {
        return new ArrayList<>(tasks.values());
    }
}
