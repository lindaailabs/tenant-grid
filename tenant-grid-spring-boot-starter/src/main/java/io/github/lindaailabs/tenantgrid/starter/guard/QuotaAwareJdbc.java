package io.github.lindaailabs.tenantgrid.starter.guard;

import io.github.lindaailabs.tenantgrid.core.quota.QuotaGuard;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把"归还配额许可"绑定到连接关闭动作上。
 *
 * <p>用代理而不是让业务代码手动释放，原因很实际：
 * 只要有一处忘记在 finally 里 close，许可就会永久泄漏，
 * 配额最终会把所有请求挡在门外——这比没有配额还糟。
 */
public final class QuotaAwareJdbc {

    private QuotaAwareJdbc() {
    }

    public static Connection wrap(Connection delegate, QuotaGuard guard, String tenantId) {
        long startNanos = System.nanoTime();
        return (Connection) Proxy.newProxyInstance(
                QuotaAwareJdbc.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new Handler(delegate, guard, tenantId, startNanos));
    }

    private static final class Handler implements InvocationHandler {

        private final Connection delegate;
        private final QuotaGuard guard;
        private final String tenantId;
        private final long startNanos;
        private final AtomicBoolean closed = new AtomicBoolean();

        Handler(Connection delegate, QuotaGuard guard, String tenantId, long startNanos) {
            this.delegate = delegate;
            this.guard = guard;
            this.tenantId = tenantId;
            this.startNanos = startNanos;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();

            if ("unwrap".equals(name) && singleClassArg(args)) {
                Class<?> target = (Class<?>) args[0];
                return target.isInstance(proxy) ? proxy : invokeDelegate(method, args);
            }
            if ("isWrapperFor".equals(name) && singleClassArg(args)) {
                Class<?> target = (Class<?>) args[0];
                return target.isInstance(proxy) ? Boolean.TRUE : invokeDelegate(method, args);
            }

            if ("close".equals(name)) {
                // 幂等：close 可能被重复调用，但许可只能归还一次
                if (!closed.compareAndSet(false, true)) {
                    return null;
                }
                try {
                    return invokeDelegate(method, args);
                } finally {
                    guard.release(tenantId);
                    guard.recordHoldTime(tenantId, heldMillis());
                }
            }

            return invokeDelegate(method, args);
        }

        private long heldMillis() {
            return (System.nanoTime() - startNanos) / 1_000_000L;
        }

        private Object invokeDelegate(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private static boolean singleClassArg(Object[] args) {
            return args != null && args.length == 1 && args[0] instanceof Class<?>;
        }
    }
}
