package io.github.lindaailabs.tenantgrid.core.migration;

import io.github.lindaailabs.tenantgrid.core.MutableMetadataProvider;
import io.github.lindaailabs.tenantgrid.core.TenantShard;
import io.github.lindaailabs.tenantgrid.core.TenantStatus;
import io.github.lindaailabs.tenantgrid.core.exception.MigrationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * 租户迁移协调器：驱动共享库 → 独立库的在线升级。
 *
 * <p><b>本库负责什么、不负责什么</b>：
 * <ul>
 *   <li>负责：阶段状态机、租户的迁移标记、切流时的路由切换、回滚</li>
 *   <li>不负责：真实的数据搬迁与一致性校验——那由 {@link DataMover} /
 *        {@link MigrationVerifier} 的SPI 实现去做（DataX / Debezium / 自研脚本）</li>
 * </ul>
 *
 * <p>硬塞一个通用搬迁实现只会变成谁都用不了的半成品：各家的表结构、
 * 增量日志格式、基础设施完全不同。这里只管编排。
 *
 * <p><b>双写由业务写路径负责</b>：协调器把租户标记为 MIGRATING 后，
 * 应用的写逻辑应通过 {@link #shouldDualWrite(String)} 判断是否需要同时写目标库。
 */
public final class MigrationCoordinator {

    private static final Logger log = LoggerFactory.getLogger(MigrationCoordinator.class);

    private final MutableMetadataProvider metadataProvider;
    private final MigrationStore store;
    private final DataMover dataMover;
    private final MigrationVerifier verifier;
    private final Runnable onMetadataChanged;

    public MigrationCoordinator(MutableMetadataProvider metadataProvider,
                                MigrationStore store,
                                DataMover dataMover,
                                MigrationVerifier verifier) {
        this(metadataProvider, store, dataMover, verifier, () -> {
        });
    }

    /**
     * @param onMetadataChanged 元数据变更后的回调，通常是缓存失效
     *                          （tenant-grid 里接 {@code CachingMetadataProvider::invalidate}）
     */
    public MigrationCoordinator(MutableMetadataProvider metadataProvider,
                                MigrationStore store,
                                DataMover dataMover,
                                MigrationVerifier verifier,
                                Runnable onMetadataChanged) {
        this.metadataProvider = Objects.requireNonNull(metadataProvider, "metadataProvider must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.dataMover = Objects.requireNonNull(dataMover, "dataMover must not be null");
        this.verifier = Objects.requireNonNull(verifier, "verifier must not be null");
        this.onMetadataChanged = Objects.requireNonNull(onMetadataChanged, "onMetadataChanged must not be null");
    }

    /**
     * 登记迁移计划并进入双写期。
     *
     * @param sourceDsKey 解析后的源库（LOGICAL 租户需先算出具体节点）
     */
    public MigrationTask start(String tenantId, String sourceDsKey, String targetDsKey) {
        TenantShard current = metadataProvider.find(tenantId);
        if (current == null) {
            throw new MigrationException(tenantId, "tenant has no shard metadata");
        }
        if (Objects.equals(sourceDsKey, targetDsKey)) {
            throw new MigrationException(tenantId,
                    "source and target are the same datasource: " + sourceDsKey);
        }

        // 标记迁移中：路由层据此知道该租户正处于双写期
        metadataProvider.register(current.withStatus(TenantStatus.MIGRATING));
        notifyMetadataChanged();

        MigrationTask task = MigrationTask
                .create(tenantId, current, sourceDsKey, targetDsKey)
                .at(MigrationStage.DUAL_WRITE, "dual write expected from now on");
        store.save(task);
        log.info("Migration started: tenant={} {} -> {}", tenantId, sourceDsKey, targetDsKey);
        return task;
    }

    /** 推进一个阶段。任何阶段失败都会把任务置为 FAILED，可随后回滚。 */
    public MigrationTask advance(String tenantId) {
        MigrationTask task = requireTask(tenantId);
        if (task.isTerminal()) {
            throw new MigrationException(tenantId, "task is already in terminal stage " + task.stage());
        }

        try {
            MigrationTask next = switch (task.stage()) {
                case INIT -> task.at(MigrationStage.DUAL_WRITE, "dual write expected");
                case DUAL_WRITE -> task.at(MigrationStage.CATCH_UP, "ready to move data");
                case CATCH_UP -> {
                    long rows = dataMover.move(tenantId, task.sourceDsKey(), task.targetDsKey());
                    yield task.at(MigrationStage.VERIFY, "moved " + rows + " rows");
                }
                case VERIFY -> verify(task);
                case CUT_OVER -> cutOver(task);
                default -> throw new MigrationException(tenantId, "unhandled stage " + task.stage());
            };
            store.save(next);
            return next;
        } catch (MigrationException e) {
            throw e;
        } catch (Exception e) {
            MigrationTask failed = task.at(MigrationStage.FAILED, "step failed: " + e);
            store.save(failed);
            throw new MigrationException(tenantId, "step " + task.stage() + " failed", e);
        }
    }

    private MigrationTask verify(MigrationTask task) throws Exception {
        MigrationVerifier.MigrationCheck check =
                verifier.verify(task.tenantId(), task.sourceDsKey(), task.targetDsKey());
        // 校验不过就停在 FAILED：切流前发现不一致，总好过切完才发现
        return check.consistent()
                ? task.at(MigrationStage.CUT_OVER, check.detail())
                : task.at(MigrationStage.FAILED, check.detail());
    }

    private MigrationTask cutOver(MigrationTask task) {
        metadataProvider.register(TenantShard.physical(task.tenantId(), task.targetDsKey()));
        notifyMetadataChanged();
        return task.at(MigrationStage.COMPLETED, "cut over to " + task.targetDsKey());
    }

    /** 回滚：还原原始分片并清除迁移标记。 */
    public MigrationTask rollback(String tenantId) {
        MigrationTask task = requireTask(tenantId);
        if (task.stage() == MigrationStage.ROLLED_BACK) {
            return task;
        }

        // 整体还原 sourceShard 而非只改 dsKey：原始分片可能是 LOGICAL
        metadataProvider.register(task.sourceShard().withStatus(TenantStatus.ACTIVE));
        notifyMetadataChanged();

        MigrationTask rolledBack =
                task.at(MigrationStage.ROLLED_BACK, "rolled back to " + task.sourceDsKey());
        store.save(rolledBack);
        log.info("Migration rolled back: tenant={} -> {}", tenantId, task.sourceDsKey());
        return rolledBack;
    }

    /** 业务写路径据此判断是否需要双写。 */
    public boolean shouldDualWrite(String tenantId) {
        MigrationTask task = store.find(tenantId);
        if (task == null) {
            return false;
        }
        return task.stage() == MigrationStage.DUAL_WRITE
                || task.stage() == MigrationStage.CATCH_UP;
    }

    public MigrationTask status(String tenantId) {
        return store.find(tenantId);
    }

    public List<MigrationTask> activeTasks() {
        return store.all().stream().filter(task -> !task.isTerminal()).toList();
    }

    private MigrationTask requireTask(String tenantId) {
        MigrationTask task = store.find(tenantId);
        if (task == null) {
            throw new MigrationException(tenantId, "no migration task found");
        }
        return task;
    }

    private void notifyMetadataChanged() {
        onMetadataChanged.run();
    }
}
