package io.github.lindaailabs.tenantgrid.starter.guard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

/**
 * 用动态代理在 JDBC 层织入两件事：<b>SQL 校验</b>与<b>执行超时</b>。
 *
 * <p>拦截两个入口：
 * <ul>
 *   <li>{@code Connection.prepareStatement(sql) / prepareCall(sql)}——
 *       绝大多数 ORM（MyBatis / JdbcTemplate）都走这条路，SQL 在这里就能拦下</li>
 *   <li>{@code Statement.execute(sql) / executeQuery(sql) / executeUpdate(sql)}——
 *       覆盖直接拼字符串执行的场景</li>
 * </ul>
 *
 * <p><b>为什么 PreparedStatement 本身不代理</b>：它的 SQL 在 prepare 阶段已校验过，
 * 再包一层意味着要以 {@code PreparedStatement} 接口生成代理，
 * 而连接池返回的实现类往往还带有自己的内部接口，代理会破坏类型。
 * 只代理普通 {@code Statement} 即可覆盖全部风险面。
 * 超时则不需要代理——在创建后调用一次 {@code setQueryTimeout} 即可。
 *
 * <p><b>超时的前提</b>：设置在 Statement 创建后、执行前。若驱动层开启了
 * PreparedStatement 缓存（如 MySQL 的 {@code cachePrepStmts=true}），statement 会被
 * 跨请求复用，上一个租户设置的超时可能残留到下一个租户。默认配置下连接池不缓存
 * statement，因此无此问题；若你开了驱动层缓存，请改用统一的全局超时，或不要依赖本机制。
 */
public final class GuardedJdbc {

    private static final Logger log = LoggerFactory.getLogger(GuardedJdbc.class);

    private static final Set<String> CONNECTION_SQL_METHODS =
            Set.of("prepareStatement", "prepareCall", "nativeSQL");

    private static final Set<String> STATEMENT_SQL_METHODS =
            Set.of("execute", "executeQuery", "executeUpdate", "addBatch");

    private GuardedJdbc() {
    }

    public static Connection wrapConnection(Connection delegate, SqlGuard guard) {
        return wrapConnection(delegate, guard, 0);
    }

    /**
     * @param guard               SQL 校验器；{@code null} 表示只织入超时
     * @param queryTimeoutSeconds 单条 SQL 的执行超时（秒）；{@code 0} 表示不限制
     */
    public static Connection wrapConnection(Connection delegate, SqlGuard guard, int queryTimeoutSeconds) {
        return (Connection) newProxy(delegate, guard, queryTimeoutSeconds,
                CONNECTION_SQL_METHODS, Connection.class);
    }

    static Statement wrapStatement(Statement delegate, SqlGuard guard, int queryTimeoutSeconds) {
        return (Statement) newProxy(delegate, guard, queryTimeoutSeconds,
                STATEMENT_SQL_METHODS, Statement.class);
    }

    private static Object newProxy(Object delegate,
                                   SqlGuard guard,
                                   int queryTimeoutSeconds,
                                   Set<String> sqlMethods,
                                   Class<?> api) {
        return Proxy.newProxyInstance(
                GuardedJdbc.class.getClassLoader(),
                new Class<?>[]{api},
                new Handler(delegate, guard, queryTimeoutSeconds, sqlMethods));
    }

    private static final class Handler implements InvocationHandler {

        private final Object delegate;
        private final SqlGuard guard;
        private final int queryTimeoutSeconds;
        private final Set<String> sqlMethods;

        Handler(Object delegate, SqlGuard guard, int queryTimeoutSeconds, Set<String> sqlMethods) {
            this.delegate = delegate;
            this.guard = guard;
            this.queryTimeoutSeconds = queryTimeoutSeconds;
            this.sqlMethods = sqlMethods;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();

            // unwrap / isWrapperFor 优先命中代理自身，
            // 否则会退化为返回未受保护的真实连接，后续 SQL 就绕过校验了
            if ("unwrap".equals(name) && hasSingleClassArg(args)) {
                Class<?> target = (Class<?>) args[0];
                return target.isInstance(proxy) ? proxy : invokeDelegate(method, args);
            }
            if ("isWrapperFor".equals(name) && hasSingleClassArg(args)) {
                Class<?> target = (Class<?>) args[0];
                return target.isInstance(proxy) ? Boolean.TRUE : invokeDelegate(method, args);
            }

            if (guard != null && args != null && args.length > 0
                    && args[0] instanceof String sql && sqlMethods.contains(name)) {
                guard.check(sql);
            }

            Object result = invokeDelegate(method, args);

            // createStatement / prepareStatement / prepareCall 三条路径都在这里收口
            if (result instanceof Statement statement) {
                applyQueryTimeout(statement);
                if (!(statement instanceof PreparedStatement)) {
                    return wrapStatement(statement, guard, queryTimeoutSeconds);
                }
                return statement;
            }
            if (result instanceof Connection connection) {
                return wrapConnection(connection, guard, queryTimeoutSeconds);
            }
            return result;
        }

        /**
         * 设置执行超时。业务代码若随后自己调用 {@code setQueryTimeout} 会覆盖这里的值——
         * 显式指定优先于全局策略，这是有意为之。
         */
        private void applyQueryTimeout(Statement statement) {
            if (queryTimeoutSeconds <= 0) {
                return;
            }
            try {
                statement.setQueryTimeout(queryTimeoutSeconds);
            } catch (SQLException e) {
                // 设置失败不该让查询失败：超时是防护手段，不是正确性问题。
                // 降级为"不限制"并留下日志，比把业务请求打挂更合理。
                log.warn("Failed to set query timeout of {}s; proceeding without a timeout",
                        queryTimeoutSeconds, e);
            }
        }

        private Object invokeDelegate(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private static boolean hasSingleClassArg(Object[] args) {
            return args != null && args.length == 1 && args[0] instanceof Class<?>;
        }
    }
}
