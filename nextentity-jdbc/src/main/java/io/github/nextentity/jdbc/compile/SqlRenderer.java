package io.github.nextentity.jdbc.compile;

import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.ValueSelection;
import io.github.nextentity.jdbc.dialect.JdbcDialect;
import io.github.nextentity.jdbc.dialect.LockClause;
import io.github.nextentity.jdbc.meta.JdbcEntityType;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import java.util.ArrayList;
import java.util.List;

/// 一条 `QuerySpec` 的 SQL 渲染器：顺序拼接子句，同时收集绑定值与结果列类型。
///
/// 子句顺序冻结：`select [distinct] … from … [join …] [where …] [group by …] [having …]
/// [order by …] [limit ? [offset ?]] [for update]`；参数顺序 = 渲染顺序
/// （SELECT 里的字面量 → WHERE → HAVING → 窗口）。方言差异只经设计 §6.1 的六个渲染点注入。
final class SqlRenderer {

    private final JdbcMeta meta;
    private final JdbcDialect dialect;
    private final QuerySpec spec;
    private final SqlPaths paths;
    private final List<Object> parameters = new ArrayList<>();
    private final List<Class<?>> columnTypes = new ArrayList<>();

    SqlRenderer(JdbcMeta meta, JdbcDialect dialect, QuerySpec spec) {
        this.meta = meta;
        this.dialect = dialect;
        this.spec = spec;
        this.paths = new SqlPaths(meta, dialect, spec.entityType());
    }

    /// 渲染普通查询。
    ///
    /// @return 渲染结果
    SqlStatement render() {
        SqlClauses clauses = new SqlClauses(this);
        String selection = new SqlProjections(this).render(spec.selection());
        String tail = clauses.where(spec.where())
                + clauses.groupBy(spec.groupBy())
                + clauses.having(spec.having())
                + clauses.orderBy(spec.orderBy())
                + clauses.window(spec.window());
        LockClause lock = clauses.lock(spec);
        return statement(selection, lock.tableSuffix(), tail + lock.statementSuffix());
    }

    /// 计数语句：`count(*)`，或单列 distinct 的 `count(distinct ...)`。
    ///
    /// @return 渲染结果
    SqlStatement count() {
        if (spec.aggregate()) {
            throw new UnsupportedOperationException(
                    "分组/聚合规格的 count 未实现（2.2.3 走子查询计数：" + spec.entityType().getName() + "）");
        }
        SqlClauses clauses = new SqlClauses(this);
        String expression = countExpression();
        // 计数结果列类型固定 Long（设计 §3.9）：执行器 getLong(1)，不按列类型推断。
        columnTypes.add(Long.class);
        return statement(expression, "", clauses.where(spec.where()));
    }

    /// 存在性语句：`select 1 ... limit 1`（单行上限由方言决定，见设计 §3.9）。
    ///
    /// @return 渲染结果
    SqlStatement exists() {
        if (spec.aggregate()) {
            throw new UnsupportedOperationException(
                    "分组/聚合规格的 exists 未实现：" + spec.entityType().getName());
        }
        SqlClauses clauses = new SqlClauses(this);
        columnTypes.add(Boolean.class);
        return statement("1", "", clauses.where(spec.where()) + window(true), true);
    }

    /// 窗口尾缀：片段由方言拼、参数经 sink 追加；两者数量不符说明方言实现有 bug，直接失败（§6.1）。
    ///
    /// @param exists 是否是 exists 查询（上限在方言手里）
    /// @return 尾片段
    String window(boolean exists) {
        List<Object> collected = new ArrayList<>();
        String fragment =
                dialect.limitClause(spec.window(), exists, !spec.orderBy().isEmpty(), collected::add);
        long placeholders = fragment.chars().filter(c -> c == '?').count();
        if (placeholders != collected.size()) {
            throw new IllegalStateException("方言片段占位符与绑定参数数量不符: ?=" + placeholders + ", bound=" + collected.size());
        }
        parameters.addAll(collected);
        return fragment;
    }

    private String countExpression() {
        if (spec.selection() instanceof ValueSelection values && values.distinct()) {
            if (values.items().size() > 1) {
                throw new UnsupportedOperationException(
                        "多列 distinct 规格的 count 未实现（列数 " + values.items().size() + "，方言语义不一致）："
                                + spec.entityType().getName());
            }
            return "count(distinct "
                    + new SqlProjections(this).expression(values.items().get(0)) + ")";
        }
        return "count(*)";
    }

    private SqlStatement statement(String projection, String tableSuffix, String tail) {
        return statement(projection, tableSuffix, tail, false);
    }

    private SqlStatement statement(String projection, String tableSuffix, String tail, boolean exists) {
        String sql = dialect.selectHead(projection, exists)
                + " from " + dialect.quote(entityType().table()) + " " + SqlPaths.ROOT_ALIAS
                + tableSuffix + paths.joins() + tail;
        return new SqlStatement(sql, parameters, columnTypes);
    }

    JdbcEntityType<?> entityType() {
        return meta.entityType(spec.entityType());
    }

    JdbcDialect dialect() {
        return dialect;
    }

    SqlPaths paths() {
        return paths;
    }

    void addParameter(Object value) {
        parameters.add(value);
    }

    void addColumnType(Class<?> type) {
        columnTypes.add(type);
    }
}
