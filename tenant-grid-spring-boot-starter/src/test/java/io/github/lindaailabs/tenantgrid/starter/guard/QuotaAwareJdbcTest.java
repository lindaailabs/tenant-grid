package io.github.lindaailabs.tenantgrid.starter.guard;

import io.github.lindaailabs.tenantgrid.core.quota.TenantQuotaGuard;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class QuotaAwareJdbcTest {

    private static final long HIGH_THRESHOLD = 10_000L;
    private static final int MAX_TRACKED = 100;

    @Test
    void releasesPermitWhenConnectionIsClosed() {
        TenantQuotaGuard guard = new TenantQuotaGuard(1, HIGH_THRESHOLD, MAX_TRACKED);
        Connection raw = mock(Connection.class);
        guard.tryAcquire("t_1");

        assertThatCode(() -> QuotaAwareJdbc.wrap(raw, guard, "t_1").close())
                .doesNotThrowAnyException();

        // 许可已归还，所以还能再拿到
        assertThat(guard.tryAcquire("t_1")).isTrue();
    }

    /** 重复 close 只能归还一次，否则许可会凭空增多，配额形同虚设。 */
    @Test
    void repeatedCloseReleasesOnlyOnce() throws Exception {
        TenantQuotaGuard guard = new TenantQuotaGuard(2, HIGH_THRESHOLD, MAX_TRACKED);
        Connection raw = mock(Connection.class);
        guard.tryAcquire("t_1");
        guard.tryAcquire("t_1");

        Connection wrapped = QuotaAwareJdbc.wrap(raw, guard, "t_1");
        wrapped.close();
        wrapped.close();

        assertThat(guard.usage().get(0).active()).isEqualTo(1);
    }

    @Test
    void closesTheUnderlyingConnection() throws Exception {
        TenantQuotaGuard guard = new TenantQuotaGuard(1, HIGH_THRESHOLD, MAX_TRACKED);
        Connection raw = mock(Connection.class);

        QuotaAwareJdbc.wrap(raw, guard, "t_1").close();

        verify(raw, times(1)).close();
    }

    @Test
    void releasesPermitEvenIfCloseFails() throws Exception {
        TenantQuotaGuard guard = new TenantQuotaGuard(1, HIGH_THRESHOLD, MAX_TRACKED);
        Connection raw = mock(Connection.class);
        org.mockito.Mockito.doThrow(new SQLException("close failed")).when(raw).close();
        guard.tryAcquire("t_1");

        assertThatThrownBy(() -> QuotaAwareJdbc.wrap(raw, guard, "t_1").close())
                .isInstanceOf(SQLException.class);

        // 关闭失败也必须归还，否则一次故障就永久泄漏一个许可
        assertThat(guard.tryAcquire("t_1")).isTrue();
    }

    @Test
    void recordsHoldTimeOnClose() {
        TenantQuotaGuard guard = new TenantQuotaGuard(1, -1L, MAX_TRACKED);
        Connection raw = mock(Connection.class);
        guard.tryAcquire("t_1");

        assertThatCode(() -> QuotaAwareJdbc.wrap(raw, guard, "t_1").close())
                .doesNotThrowAnyException();

        assertThat(guard.usage().get(0).slowHolds()).isEqualTo(1);
    }

    @Test
    void passesThroughUnrelatedCalls() throws Exception {
        TenantQuotaGuard guard = new TenantQuotaGuard(1, HIGH_THRESHOLD, MAX_TRACKED);
        Connection raw = mock(Connection.class);

        QuotaAwareJdbc.wrap(raw, guard, "t_1").setAutoCommit(false);

        verify(raw).setAutoCommit(false);
        verify(raw, never()).close();
    }

    @Test
    void unwrapReturnsTheProxyItself() throws Exception {
        TenantQuotaGuard guard = new TenantQuotaGuard(1, HIGH_THRESHOLD, MAX_TRACKED);
        Connection raw = mock(Connection.class);

        Connection wrapped = QuotaAwareJdbc.wrap(raw, guard, "t_1");

        assertThat(wrapped.unwrap(Connection.class)).isSameAs(wrapped);
        assertThat(wrapped.isWrapperFor(Connection.class)).isTrue();
    }
}
