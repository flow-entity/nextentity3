package io.github.nextentity.jdbc.compile;

import io.github.nextentity.core.spec.CountScope;
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
/// （SELECT 里的字面量 → WHERE → HAVING → 窗口）。方言差异只经设计 §6.1 的方言钩子注入。
@SuppressWarnings("PMD.TooManyMethods") // 渲染点由 QuerySpec / Selection / Operand 的节点集合决定，拆开要把同一段子句顺序散成多段
final class SqlRenderer {

    private static final String FROM = " from ";

    private final JdbcMeta meta;
    private final JdbcDialect dialect;
    private final QuerySpec spec;
    /// 条件改删（`UPDATE` / `DELETE`）的根实体：非 null 时没有 `QuerySpec`，只渲染根表列。
    private final JdbcEntityType<?> fixedEntityType;
    private final SqlPaths paths;
    private final List<Object> parameters = new ArrayList<>();
    private final List<Class<?>> columnTypes = new ArrayList<>();

    SqlRenderer(JdbcMeta meta, JdbcDialect dialect, QuerySpec spec) {
        this(meta, dialect, spec, null);
    }

    /// 条件改删用的渲染器：只解析根表列、不 join、不带别名。
    ///
    /// @param entityType 根实体
    /// @param dialect 方言
    /// @return 渲染器
    static SqlRenderer conditional(JdbcEntityType<?> entityType, JdbcDialect dialect) {
        return new SqlRenderer(null, dialect, null, entityType);
    }

    private SqlRenderer(JdbcMeta meta, JdbcDialect dialect, QuerySpec spec, JdbcEntityType<?> fixedEntityType) {
        this.meta = meta;
        this.dialect = dialect;
        this.spec = spec;
        this.fixedEntityType = fixedEntityType;
        this.paths = fixedEntityType == null
                ? new SqlPaths(meta, dialect, spec.entityType())
                : new SqlPaths(fixedEntityType, dialect);
    }

    /// 渲染普通查询。
    ///
    /// @return 渲染结果
    SqlStatement render() {
        QuerySpec query = spec();
        SqlClauses clauses = new SqlClauses(this);
        String selection = new SqlProjections(this).render(query.selection());
        String tail = clauses.where(query.where())
                + clauses.groupBy(query.groupBy())
                + clauses.having(query.having())
                + clauses.orderBy(query.orderBy())
                + clauses.window(query.window());
        LockClause lock = clauses.lock(query);
        return statement(selection, lock.tableSuffix(), tail + lock.statementSuffix());
    }

    /// 计数语句：`count(*)`，或单列 distinct 的 `count(distinct ...)`。
    ///
    /// @return 渲染结果
    SqlStatement count() {
        QuerySpec query = spec();
        if (query.countScope() == CountScope.GROUPS) {
            return groupedCount(query);
        }
        if (query.aggregate()) {
            throw new UnsupportedOperationException(
                    "分组/聚合规格的 count 未实现（2.2.3 走子查询计数：" + query.entityType().getName() + "）");
        }
        SqlClauses clauses = new SqlClauses(this);
        String expression = countExpression(query);
        // 计数结果列类型固定 Long：执行器 getLong(1)，不按列类型推断。
        columnTypes.add(Long.class);
        return statement(expression, "", clauses.where(query.where()));
    }

    /// 组数：`select count(*) from (select <分组键> … group by … having …) grouped`。
    ///
    /// 派生表要别名（H2 / MySQL / PG / SQL Server 都要求），否则四家都报语法错。
    private SqlStatement groupedCount(QuerySpec query) {
        SqlClauses clauses = new SqlClauses(this);
        // 内层只取分组键（与 group by 同形），不额外登记结果列类型：外层 count(*) 的结果列是 Long。
        List<String> keys = query.groupBy().stream()
                .map(key -> paths.resolve(key).expression())
                .toList();
        String inner = "select " + String.join(", ", keys)
                + FROM + table() + " " + SqlPaths.ROOT_ALIAS + paths.joins()
                + clauses.where(query.where())
                + clauses.groupBy(query.groupBy())
                + clauses.having(query.having());
        columnTypes.add(Long.class);
        return new SqlStatement("select count(*) from (" + inner + ") grouped", parameters, columnTypes);
    }

    /// 存在性语句：`select 1 ... limit 1`（单行上限由方言决定）。
    ///
    /// @return 渲染结果
    SqlStatement exists() {
        QuerySpec query = spec();
        SqlClauses clauses = new SqlClauses(this);
        columnTypes.add(Boolean.class);
        if (query.countScope() == CountScope.GROUPS) {
            // 是否有任何组：保留 group by / having，限 1 行（SQL Server 由 selectHead 出 top (1)）。
            return statement(
                    "1",
                    "",
                    clauses.where(query.where())
                            + clauses.groupBy(query.groupBy())
                            + clauses.having(query.having())
                            + window(true),
                    true);
        }
        if (query.aggregate()) {
            throw new UnsupportedOperationException(
                    "分组/聚合规格的 exists 未实现：" + query.entityType().getName());
        }
        return statement("1", "", clauses.where(query.where()) + window(true), true);
    }

    /// 窗口尾缀：片段由方言拼、参数经 sink 追加；两者数量不符说明方言实现有 bug，直接失败（见 DESIGN §6.1）。
    ///
    /// @param exists 是否是 exists 查询（上限在方言手里）
    /// @return 尾片段
    String window(boolean exists) {
        QuerySpec query = spec();
        List<Object> collected = new ArrayList<>();
        String fragment =
                dialect.limitClause(query.window(), exists, !query.orderBy().isEmpty(), collected::add);
        long placeholders = fragment.chars().filter(c -> c == '?').count();
        if (placeholders != collected.size()) {
            throw new IllegalStateException("方言片段占位符与绑定参数数量不符: ?=" + placeholders + ", bound=" + collected.size());
        }
        parameters.addAll(collected);
        return fragment;
    }

    private String countExpression(QuerySpec query) {
        if (query.selection() instanceof ValueSelection values && values.distinct()) {
            if (values.items().size() > 1) {
                throw new UnsupportedOperationException(
                        "多列 distinct 规格的 count 未实现（列数 " + values.items().size() + "，方言语义不一致）："
                                + query.entityType().getName());
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
                + FROM + table() + " " + SqlPaths.ROOT_ALIAS
                + tableSuffix + paths.joins() + tail;
        return new SqlStatement(sql, parameters, columnTypes);
    }

    /// 根表标识符（quoted 过方言）。
    String table() {
        JdbcEntityType<?> entityType = entityType();
        return entityType.isTableQuoted() ? dialect.quote(entityType.table()) : entityType.table();
    }

    List<Object> parameters() {
        return parameters;
    }

    private QuerySpec spec() {
        // 条件改删走 conditional 工厂（spec 为 null），但它只经 ConditionalSql 的 update/delete 使用，不调用本方法。
        if (spec == null) {
            throw new IllegalStateException("条件改删渲染器不生成查询语句");
        }
        return spec;
    }

    JdbcEntityType<?> entityType() {
        return fixedEntityType != null ? fixedEntityType : meta.entityType(spec.entityType());
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
