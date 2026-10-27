package io.github.nextentity.jdbc.compile;

import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.ValueSelection;
import io.github.nextentity.jdbc.meta.JdbcEntityType;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import java.util.ArrayList;
import java.util.List;

/// 一条 `QuerySpec` 的 SQL 渲染器：顺序拼接子句，同时收集绑定值与结果列类型。
///
/// 子句顺序冻结：`select [distinct] … from … [join …] [where …] [group by …] [having …]
/// [order by …] [limit ? [offset ?]] [for update]`；参数顺序 = 渲染顺序
/// （SELECT 里的字面量 → WHERE → HAVING → 窗口）。
final class SqlRenderer {

    private final JdbcMeta meta;
    private final QuerySpec spec;
    private final SqlPaths paths;
    private final List<Object> parameters = new ArrayList<>();
    private final List<Class<?>> columnTypes = new ArrayList<>();

    SqlRenderer(JdbcMeta meta, QuerySpec spec) {
        this.meta = meta;
        this.spec = spec;
        this.paths = new SqlPaths(meta, spec.entityType());
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
                + clauses.window(spec.window())
                + clauses.lock(spec);
        return statement(selection, tail);
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
        return statement(expression, clauses.where(spec.where()));
    }

    /// 存在性语句：`select 1 ... limit 1`。
    ///
    /// @return 渲染结果
    SqlStatement exists() {
        if (spec.aggregate()) {
            throw new UnsupportedOperationException(
                    "分组/聚合规格的 exists 未实现：" + spec.entityType().getName());
        }
        SqlClauses clauses = new SqlClauses(this);
        columnTypes.add(Boolean.class);
        return statement("1", clauses.where(spec.where()) + " limit 1");
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

    private SqlStatement statement(String projection, String tail) {
        String sql = "select " + projection + " from " + entityType().table() + " " + SqlPaths.ROOT_ALIAS
                + paths.joins() + tail;
        return new SqlStatement(sql, parameters, columnTypes);
    }

    JdbcEntityType<?> entityType() {
        return meta.entityType(spec.entityType());
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
