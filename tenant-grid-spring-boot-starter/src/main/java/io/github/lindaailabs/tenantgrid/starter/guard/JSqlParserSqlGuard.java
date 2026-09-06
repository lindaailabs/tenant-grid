package io.github.lindaailabs.tenantgrid.starter.guard;

import io.github.lindaailabs.tenantgrid.core.exception.TenantIdMissingException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 基于 JSqlParser 的 SQL 校验器：解析出 AST，检查过滤条件里是否带租户列。
 *
 * <p><b>实现说明（重要）</b>：JSqlParser 的 {@code ExpressionVisitor} 派发在 5.x 上不可靠
 * （{@code expression.accept(visitor)} 不会回调到自定义 visitor），因此这里只用
 * <b>已验证可用的直接 getter</b> 来定位过滤区域：
 * WHERE / HAVING / JOIN ON / INSERT 列 / 派生表子查询，
 * 再在这些区域的**AST 节点文本**上做标识符分词匹配。
 *
 * <p>相比对原始 SQL 做正则：区域由 AST 精确定位，不会把 SELECT 投影列、
 * 表名、字符串字面量误当成过滤条件。
 *
 * <p>三类放行规则：
 * <ul>
 *   <li>非 DML（DDL / SHOW / SET 等）不校验</li>
 *   <li>无表的语句（{@code SELECT 1}）不校验——健康检查常用</li>
 *   <li>解析失败或遇到无法下钻的结构（如 UNION 分支）时放行——
 *       <b>宁可漏判，也不能阻断正常业务</b></li>
 * </ul>
 */
public final class JSqlParserSqlGuard implements SqlGuard {

    private static final Logger log = LoggerFactory.getLogger(JSqlParserSqlGuard.class);

    private final String tenantColumn;
    private final SqlGuardMode mode;
    private final List<Pattern> exemptTablePatterns;

    public JSqlParserSqlGuard(String tenantColumn, SqlGuardMode mode, List<String> exemptTablePatterns) {
        this.tenantColumn = Objects.requireNonNull(tenantColumn, "tenantColumn must not be null");
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
        this.exemptTablePatterns = compilePatterns(exemptTablePatterns);
    }

    @Override
    public void check(String sql) {
        if (mode == SqlGuardMode.OFF || sql == null || sql.isBlank()) {
            return;
        }

        switch (inspect(sql)) {
            case OK, EXEMPT -> {
                // 合规或本就无需校验
            }
            case UNPARSEABLE -> log.warn(
                    "Could not parse SQL, skipping tenant-column check: {}", sql);
            case MISSING -> {
                String message = "SQL does not reference the tenant column '" + tenantColumn + "': " + sql;
                if (mode == SqlGuardMode.ENFORCE) {
                    throw new TenantIdMissingException(message, sql);
                }
                log.warn(message);
            }
        }
    }

    private CheckResult inspect(String sql) {
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            return CheckResult.UNPARSEABLE;
        }

        if (!isDml(statement)) {
            return CheckResult.EXEMPT;
        }

        List<String> tables;
        try {
            tables = new TablesNamesFinder().getTableList(statement);
        } catch (Exception e) {
            return CheckResult.UNPARSEABLE;
        }

        if (tables.isEmpty()) {
            return CheckResult.EXEMPT;
        }
        if (tables.stream().anyMatch(this::isExempt)) {
            return CheckResult.EXEMPT;
        }

        FilterCollector collector = new FilterCollector();
        collector.collect(statement);
        if (collector.mentions(tenantColumn)) {
            return CheckResult.OK;
        }
        // 遇到无法下钻的结构时不贸然判定缺失
        return collector.incomplete() ? CheckResult.EXEMPT : CheckResult.MISSING;
    }

    private static boolean isDml(Statement statement) {
        return statement instanceof PlainSelect
                || statement instanceof SetOperationList
                || statement instanceof Insert
                || statement instanceof Update
                || statement instanceof Delete;
    }

    private boolean isExempt(String tableName) {
        String bare = tableName
                .replace("`", "")
                .replace("\"", "")
                .replace("[", "")
                .replace("]", "");
        for (Pattern pattern : exemptTablePatterns) {
            if (pattern.matcher(bare).matches()) {
                return true;
            }
        }
        return false;
    }

    private static List<Pattern> compilePatterns(List<String> patterns) {
        List<Pattern> compiled = new ArrayList<>();
        if (patterns == null) {
            return compiled;
        }
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isBlank()) {
                compiled.add(Pattern.compile(globToRegex(pattern), Pattern.CASE_INSENSITIVE));
            }
        }
        return compiled;
    }

    /** 支持 {@code *} 与 {@code ?} 通配，其余字符按字面量处理。 */
    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return regex.toString();
    }

    private enum CheckResult {
        OK,
        MISSING,
        EXEMPT,
        UNPARSEABLE
    }

    /**
     * 沿 AST 收集"起过滤作用"的区域文本。
     *
     * <p>刻意<b>不</b>收集 SELECT 投影列：{@code SELECT tenant_id FROM orders WHERE id = ?}
     * 看似带了租户列，但投影列不起过滤作用，查询照样跨租户。
     */
    private static final class FilterCollector {

        private static final Pattern STRING_LITERAL = Pattern.compile("'[^']*'");
        private static final Pattern DIGITS = Pattern.compile("\\d+");
        private static final Pattern NON_IDENTIFIER = Pattern.compile("[^A-Za-z0-9_]+");

        private final StringBuilder filters = new StringBuilder();
        private boolean incomplete;

        void collect(Object node) {
            if (node instanceof PlainSelect select) {
                collectFrom(select);
            } else if (node instanceof Insert insert) {
                collectFrom(insert);
            } else if (node instanceof Update update) {
                append(update.getWhere());
            } else if (node instanceof Delete delete) {
                append(delete.getWhere());
            } else if (node instanceof SetOperationList) {
                // UNION 各分支的过滤条件不做拆分，标记为无法确定 → 放行
                incomplete = true;
            }
        }

        private void collectFrom(PlainSelect select) {
            append(select.getWhere());
            append(select.getHaving());

            if (select.getJoins() != null) {
                for (Join join : select.getJoins()) {
                    if (join.getOnExpressions() != null) {
                        join.getOnExpressions().forEach(this::append);
                    }
                }
            }

            // 派生表 / 子查询：递归进去取它自己的 WHERE
            if (select.getFromItem() instanceof ParenthesedSelect derived) {
                collect(derived.getSelect().getSelectBody());
            }
        }

        private void collectFrom(Insert insert) {
            if (insert.getColumns() != null) {
                insert.getColumns().forEach(column -> append(column.getColumnName()));
            }
            if (insert.getSelect() != null) {
                collect(insert.getSelect().getSelectBody());
            }
        }

        private void append(Expression expression) {
            if (expression != null) {
                filters.append(' ').append(expression);
            }
        }

        private void append(String text) {
            if (text != null) {
                filters.append(' ').append(text);
            }
        }

        /** 在收集到的过滤区域文本里找租户列标识符。 */
        boolean mentions(String tenantColumn) {
            String stripped = STRING_LITERAL.matcher(filters).replaceAll(" ");
            stripped = DIGITS.matcher(stripped).replaceAll(" ");
            for (String token : NON_IDENTIFIER.split(stripped)) {
                if (tenantColumn.equalsIgnoreCase(token)) {
                    return true;
                }
            }
            return false;
        }

        boolean incomplete() {
            return incomplete;
        }
    }
}
