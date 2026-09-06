package io.github.lindaailabs.tenantgrid.core;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.alibaba.ttl.TtlCallable;
import com.alibaba.ttl.TtlRunnable;
import io.github.lindaailabs.tenantgrid.core.exception.MissingTenantContextException;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * 当前调用链绑定的租户。
 *
 * <p>使用 {@link TransmittableThreadLocal} 而非普通 {@code ThreadLocal}，
 * 原因很实际：业务代码一旦走线程池 / {@code @Async} / 并行流，
 * 普通 ThreadLocal 会静默丢失租户，路由随即回落默认库——结果是跨租户数据污染。
 *
 * <p>提交异步任务时用 {@link #wrap(Runnable)} / {@link #wrap(Callable)} 包装，
 * 上下文即可跨线程池传播：
 *
 * <pre>{@code
 * executor.submit(TenantContext.wrap(() -> orderService.create(..)));
 * }</pre>
 */
public final class TenantContext {

    private static final TransmittableThreadLocal<String> HOLDER = new TransmittableThreadLocal<>();

    private TenantContext() {
    }

    /** 绑定租户；传 null 或空白等价于解绑。 */
    public static void set(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            HOLDER.remove();
        } else {
            HOLDER.set(tenantId);
        }
    }

    /** 当前租户，未绑定时返回 {@code null}。 */
    public static String currentId() {
        return HOLDER.get();
    }

    /** 当前租户，未绑定时抛 {@link MissingTenantContextException}。 */
    public static String require() {
        String tenantId = HOLDER.get();
        if (tenantId == null) {
            throw new MissingTenantContextException();
        }
        return tenantId;
    }

    /** 解绑当前租户。务必在 finally 中调用，避免线程复用时串租户。 */
    public static void clear() {
        HOLDER.remove();
    }

    /** 以指定租户执行，结束后恢复原上下文。 */
    public static <T> T runAs(String tenantId, Supplier<T> action) {
        String previous = HOLDER.get();
        set(tenantId);
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    /** 以指定租户执行，结束后恢复原上下文。 */
    public static void runAs(String tenantId, Runnable action) {
        String previous = HOLDER.get();
        set(tenantId);
        try {
            action.run();
        } finally {
            restore(previous);
        }
    }

    /** 包装任务，使其在线程池中仍能读到提交时的租户。 */
    public static Runnable wrap(Runnable task) {
        return TtlRunnable.get(task);
    }

    /** 包装任务，使其在线程池中仍能读到提交时的租户。 */
    public static <T> Callable<T> wrap(Callable<T> task) {
        return TtlCallable.get(task);
    }

    private static void restore(String previous) {
        if (previous == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(previous);
        }
    }
}
