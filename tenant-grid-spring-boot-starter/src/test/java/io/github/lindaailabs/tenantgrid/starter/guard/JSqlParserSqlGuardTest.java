package io.github.lindaailabs.tenantgrid.starter.guard;

import io.github.lindaailabs.tenantgrid.core.exception.TenantIdMissingException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JSqlParserSqlGuardTest {

    private static final String TENANT_COLUMN = "tenant_id";

    private SqlGuard enforcing() {
        return new JSqlParserSqlGuard(TENANT_COLUMN, SqlGuardMode.ENFORCE, List.of());
    }

    private void assertAccepted(SqlGuard guard, String sql) {
        assertThatCode(() -> guard.check(sql))
                .as("should accept: %s", sql)
                .doesNotThrowAnyException();
    }

    private void assertRejected(SqlGuard guard, String sql) {
        assertThatThrownBy(() -> guard.check(sql))
                .as("should reject: %s", sql)
                .isInstanceOf(TenantIdMissingException.class);
    }

    @Test
    void acceptsSelectWithTenantColumn() {
        assertAccepted(enforcing(), "SELECT * FROM orders WHERE tenant_id = ?");
    }

    @Test
    void acceptsAliasedTenantColumn() {
        assertAccepted(enforcing(), "SELECT * FROM orders o WHERE o.tenant_id = ?");
    }

    @Test
    void acceptsTenantColumnAmongOtherPredicates() {
        assertAccepted(enforcing(), "SELECT * FROM orders WHERE status = ? AND tenant_id = ?");
    }

    @Test
    void acceptsCaseInsensitiveColumnName() {
        assertAccepted(enforcing(), "SELECT * FROM orders WHERE TENANT_ID = ?");
    }

    @Test
    void acceptsInsertWithTenantColumn() {
        assertAccepted(enforcing(), "INSERT INTO orders (tenant_id, amount) VALUES (?, ?)");
    }

    @Test
    void acceptsUpdateWithTenantColumn() {
        assertAccepted(enforcing(), "UPDATE orders SET amount = ? WHERE tenant_id = ?");
    }

    @Test
    void acceptsDeleteWithTenantColumn() {
        assertAccepted(enforcing(), "DELETE FROM orders WHERE tenant_id = ?");
    }

    /** 子查询是正则方案必然漏掉的场景，走 AST 才能覆盖。 */
    @Test
    void acceptsTenantColumnInsideSubquery() {
        assertAccepted(enforcing(),
                "SELECT * FROM (SELECT * FROM orders WHERE tenant_id = ?) t WHERE t.amount > 10");
    }

    @Test
    void acceptsTenantColumnInJoinCondition() {
        assertAccepted(enforcing(),
                "SELECT * FROM orders o JOIN items i ON o.id = i.order_id WHERE o.tenant_id = ?");
    }

    @Test
    void rejectsSelectWithoutTenantColumn() {
        assertRejected(enforcing(), "SELECT * FROM orders WHERE id = ?");
    }

    @Test
    void rejectsUnconditionalSelect() {
        assertRejected(enforcing(), "SELECT * FROM orders");
    }

    @Test
    void rejectsInsertWithoutTenantColumn() {
        assertRejected(enforcing(), "INSERT INTO orders (amount) VALUES (?)");
    }

    @Test
    void rejectsUpdateWithoutTenantColumn() {
        assertRejected(enforcing(), "UPDATE orders SET amount = ? WHERE id = ?");
    }

    @Test
    void rejectsDeleteWithoutTenantColumn() {
        assertRejected(enforcing(), "DELETE FROM orders WHERE id = ?");
    }

    @Test
    void rejectsJoinWithoutTenantColumn() {
        assertRejected(enforcing(),
                "SELECT * FROM orders o JOIN items i ON o.id = i.order_id WHERE o.id = ?");
    }

    @Test
    void rejectsTenantColumnOnlyInSelectList() {
        // 只在投影里出现不算过滤条件，仍然会跨租户
        assertRejected(enforcing(), "SELECT tenant_id FROM orders WHERE id = ?");
    }

    @Test
    void ignoresStatementsWithoutTables() {
        // 健康检查常用 SELECT 1，不能误伤
        assertAccepted(enforcing(), "SELECT 1");
    }

    @Test
    void ignoresDdl() {
        assertAccepted(enforcing(), "CREATE TABLE orders (id INT, tenant_id VARCHAR(36))");
        assertAccepted(enforcing(), "ALTER TABLE orders ADD COLUMN note VARCHAR(255)");
    }

    @Test
    void honoursExemptTablePatterns() {
        SqlGuard guard = new JSqlParserSqlGuard(TENANT_COLUMN, SqlGuardMode.ENFORCE, List.of("sys_*"));

        assertAccepted(guard, "SELECT * FROM sys_dict WHERE id = ?");
        assertRejected(guard, "SELECT * FROM orders WHERE id = ?");
    }

    @Test
    void honoursCustomTenantColumn() {
        SqlGuard guard = new JSqlParserSqlGuard("seller_id", SqlGuardMode.ENFORCE, List.of());

        assertAccepted(guard, "SELECT * FROM orders WHERE seller_id = ?");
        assertRejected(guard, "SELECT * FROM orders WHERE tenant_id = ?");
    }

    @Test
    void offModeNeverBlocks() {
        SqlGuard guard = new JSqlParserSqlGuard(TENANT_COLUMN, SqlGuardMode.OFF, List.of());

        assertAccepted(guard, "SELECT * FROM orders WHERE id = ?");
    }

    @Test
    void warnModeDoesNotThrow() {
        SqlGuard guard = new JSqlParserSqlGuard(TENANT_COLUMN, SqlGuardMode.WARN, List.of());

        assertAccepted(guard, "SELECT * FROM orders WHERE id = ?");
    }

    /** 解析失败时放行并告警：不能因为解析器不支持某语法就阻断业务。 */
    @Test
    void unparseableSqlIsAllowed() {
        assertAccepted(enforcing(), "THIS IS NOT SQL AT ALL !!!");
    }

    @Test
    void blankSqlIsIgnored() {
        SqlGuard guard = enforcing();

        assertAccepted(guard, "");
        assertAccepted(guard, "   ");
        assertAccepted(guard, null);
    }

    @Test
    void rejectedExceptionCarriesTheSql() {
        String sql = "SELECT * FROM orders WHERE id = ?";

        assertThatThrownBy(() -> enforcing().check(sql))
                .isInstanceOf(TenantIdMissingException.class)
                .hasMessageContaining("tenant_id")
                .extracting(e -> ((TenantIdMissingException) e).getSql())
                .isEqualTo(sql);
    }
}
