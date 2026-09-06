package io.github.lindaailabs.tenantgrid.starter.guard;

import io.github.lindaailabs.tenantgrid.core.exception.TenantIdMissingException;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuardedJdbcTest {

    private static final class RecordingGuard implements SqlGuard {

        private final List<String> seen = new ArrayList<>();

        @Override
        public void check(String sql) {
            seen.add(sql);
        }
    }

    private SqlGuard enforcing() {
        return new JSqlParserSqlGuard("tenant_id", SqlGuardMode.ENFORCE, List.of());
    }

    @Test
    void interceptsPrepareStatement() throws Exception {
        RecordingGuard guard = new RecordingGuard();
        Connection raw = mock(Connection.class);
        when(raw.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));

        GuardedJdbc.wrapConnection(raw, guard)
                .prepareStatement("SELECT * FROM orders WHERE id = 1");

        assertThat(guard.seen).containsExactly("SELECT * FROM orders WHERE id = 1");
    }

    @Test
    void interceptsPrepareCall() throws Exception {
        RecordingGuard guard = new RecordingGuard();
        Connection raw = mock(Connection.class);

        assertThatCode(() -> GuardedJdbc.wrapConnection(raw, guard).prepareCall("{call refresh(?)}"))
                .doesNotThrowAnyException();
        assertThat(guard.seen).containsExactly("{call refresh(?)}");
    }

    @Test
    void leavesUnrelatedMethodsUntouched() throws Exception {
        RecordingGuard guard = new RecordingGuard();
        Connection raw = mock(Connection.class);

        GuardedJdbc.wrapConnection(raw, guard).close();

        assertThat(guard.seen).isEmpty();
    }

    @Test
    void blocksBadSqlInEnforceMode() {
        Connection raw = mock(Connection.class);

        assertThatThrownBy(() -> GuardedJdbc.wrapConnection(raw, enforcing())
                .prepareStatement("SELECT * FROM orders WHERE id = 1"))
                .isInstanceOf(TenantIdMissingException.class);
    }

    @Test
    void allowsGoodSqlInEnforceMode() {
        Connection raw = mock(Connection.class);

        assertThatCode(() -> GuardedJdbc.wrapConnection(raw, enforcing())
                .prepareStatement("SELECT * FROM orders WHERE tenant_id = ?"))
                .doesNotThrowAnyException();
    }

    /** 直接拼字符串执行的场景由 Statement 层兜住。 */
    @Test
    void interceptsPlainStatementExecution() throws Exception {
        RecordingGuard guard = new RecordingGuard();
        Connection raw = mock(Connection.class);
        Statement rawStatement = mock(Statement.class);
        when(raw.createStatement()).thenReturn(rawStatement);

        GuardedJdbc.wrapConnection(raw, guard)
                .createStatement()
                .execute("DELETE FROM orders WHERE id = 1");

        assertThat(guard.seen).containsExactly("DELETE FROM orders WHERE id = 1");
    }

    @Test
    void blocksBadSqlOnPlainStatement() throws Exception {
        Connection raw = mock(Connection.class);
        Statement rawStatement = mock(Statement.class);
        when(raw.createStatement()).thenReturn(rawStatement);

        Connection guarded = GuardedJdbc.wrapConnection(raw, enforcing());
        assertThatThrownBy(() -> {
            try (Statement statement = guarded.createStatement()) {
                statement.executeQuery("SELECT * FROM orders WHERE id = 1");
            }
        }).isInstanceOf(TenantIdMissingException.class);
    }

    /**
     * unwrap 必须返回被保护的代理本身。
     * 若退化到真实连接，后续拿到的连接就绕过了校验——防护形同虚设。
     */
    @Test
    void unwrapReturnsTheGuardedProxy() throws Exception {
        Connection raw = mock(Connection.class);
        Connection guarded = GuardedJdbc.wrapConnection(raw, new RecordingGuard());

        assertThat(guarded.unwrap(Connection.class)).isSameAs(guarded);
        assertThat(guarded.isWrapperFor(Connection.class)).isTrue();
    }

    @Test
    void propagatesSqlExceptionsFromDelegate() throws Exception {
        Connection raw = mock(Connection.class);
        when(raw.prepareStatement(anyString())).thenThrow(new SQLException("boom"));

        Connection guarded = GuardedJdbc.wrapConnection(raw, new RecordingGuard());

        assertThatThrownBy(() -> guarded.prepareStatement("SELECT 1"))
                .isInstanceOf(SQLException.class)
                .hasMessage("boom");
    }

    /** 超时不依赖 SQL 校验：只开超时时也必须织入。 */
    @Test
    void appliesTimeoutToPreparedStatement() throws Exception {
        PreparedStatement rawStatement = mock(PreparedStatement.class);
        Connection raw = mock(Connection.class);
        when(raw.prepareStatement(anyString())).thenReturn(rawStatement);

        GuardedJdbc.wrapConnection(raw, null, 30).prepareStatement("SELECT 1");

        verify(rawStatement).setQueryTimeout(30);
    }

    /** createStatement 这条路径同样要覆盖——直接拼字符串执行的场景。 */
    @Test
    void appliesTimeoutToPlainStatement() throws Exception {
        Statement rawStatement = mock(Statement.class);
        Connection raw = mock(Connection.class);
        when(raw.createStatement()).thenReturn(rawStatement);

        GuardedJdbc.wrapConnection(raw, null, 15).createStatement();

        verify(rawStatement).setQueryTimeout(15);
    }

    @Test
    void appliesTimeoutAlongsideSqlCheck() throws Exception {
        RecordingGuard guard = new RecordingGuard();
        PreparedStatement rawStatement = mock(PreparedStatement.class);
        Connection raw = mock(Connection.class);
        when(raw.prepareStatement(anyString())).thenReturn(rawStatement);

        GuardedJdbc.wrapConnection(raw, guard, 20).prepareStatement("SELECT 1 WHERE tenant_id = 1");

        assertThat(guard.seen).containsExactly("SELECT 1 WHERE tenant_id = 1");
        verify(rawStatement).setQueryTimeout(20);
    }

    /** 0 表示不限时，此时不能去覆盖驱动 / 连接池自己的超时设置。 */
    @Test
    void leavesTimeoutAloneWhenDisabled() throws Exception {
        PreparedStatement rawStatement = mock(PreparedStatement.class);
        Connection raw = mock(Connection.class);
        when(raw.prepareStatement(anyString())).thenReturn(rawStatement);

        GuardedJdbc.wrapConnection(raw, null, 0).prepareStatement("SELECT 1");

        verify(rawStatement, never()).setQueryTimeout(anyInt());
    }

    /** 设置超时失败不该让查询失败——超时是防护手段，不是正确性问题。 */
    @Test
    void survivesQueryTimeoutFailure() throws Exception {
        PreparedStatement rawStatement = mock(PreparedStatement.class);
        doThrow(new SQLException("driver does not support query timeout"))
                .when(rawStatement).setQueryTimeout(anyInt());
        Connection raw = mock(Connection.class);
        when(raw.prepareStatement(anyString())).thenReturn(rawStatement);

        assertThatCode(() -> GuardedJdbc.wrapConnection(raw, null, 30).prepareStatement("SELECT 1"))
                .doesNotThrowAnyException();
    }
}
