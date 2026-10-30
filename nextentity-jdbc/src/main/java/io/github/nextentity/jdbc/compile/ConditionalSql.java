package io.github.nextentity.jdbc.compile;

import io.github.nextentity.core.spec.Assignment;
import io.github.nextentity.core.spec.DeleteSpec;
import io.github.nextentity.core.spec.UpdateSpec;
import io.github.nextentity.jdbc.dialect.JdbcDialect;
import io.github.nextentity.jdbc.meta.JdbcEntityAttribute;
import io.github.nextentity.jdbc.meta.JdbcEntityType;
import io.github.nextentity.jdbc.meta.JdbcTypes;
import java.util.ArrayList;
import java.util.List;

/// 条件改删：`UPDATE t SET … WHERE …` / `DELETE FROM t WHERE …`。
///
/// 与查询不同，列引用不带表别名（`UPDATE` / `DELETE` 的别名语法各库不一致，见设计 §7.1 CUT 表）；
/// 赋值值一律参数绑定，`null` 渲染成 `SET c = NULL`，不跳过该列。
///
/// 公开原因同 {@link SqlStatement}：语句由 `exec` 包的写入器执行。
public final class ConditionalSql {

    private static final String WHERE = " where ";

    private ConditionalSql() {}

    /// 渲染条件更新。
    ///
    /// @param spec 更新规格
    /// @param entityType 根实体
    /// @param dialect 方言
    /// @return 语句
    public static SqlStatement update(UpdateSpec spec, JdbcEntityType<?> entityType, JdbcDialect dialect) {
        SqlRenderer renderer = SqlRenderer.conditional(entityType, dialect);
        List<Object> setParameters = new ArrayList<>();
        List<String> assignments = new ArrayList<>();
        for (Assignment assignment : spec.assignments()) {
            assignments.add(assignment(renderer, assignment, setParameters));
        }
        String where = spec.where() == null ? "" : WHERE + new SqlPredicates(renderer).render(spec.where());
        List<Object> parameters = new ArrayList<>(setParameters);
        parameters.addAll(renderer.parameters());
        return new SqlStatement(
                "update " + renderer.table() + " set " + String.join(", ", assignments) + where, parameters, List.of());
    }

    /// 渲染条件删除；没有 `where` 就是整表删除（2.2.3 语义，不额外拒绝）。
    ///
    /// @param spec 删除规格
    /// @param entityType 根实体
    /// @param dialect 方言
    /// @return 语句
    public static SqlStatement delete(DeleteSpec spec, JdbcEntityType<?> entityType, JdbcDialect dialect) {
        SqlRenderer renderer = SqlRenderer.conditional(entityType, dialect);
        String where = spec.where() == null ? "" : WHERE + new SqlPredicates(renderer).render(spec.where());
        return new SqlStatement("delete from " + renderer.table() + where, renderer.parameters(), List.of());
    }

    private static String assignment(SqlRenderer renderer, Assignment assignment, List<Object> parameters) {
        SqlPaths.Resolved resolved = renderer.paths().resolve(assignment.path());
        JdbcEntityAttribute attribute = resolved.attribute();
        if (!attribute.isColumnUpdatable()) {
            throw new UnsupportedOperationException("条件更新不支持 @Column(updatable=false) 列: " + path(assignment));
        }
        if (assignment.value() == null) {
            return resolved.expression() + "=NULL";
        }
        parameters.add(JdbcTypes.toLiteral(assignment.value(), attribute));
        return resolved.expression() + "=?";
    }

    private static String path(Assignment assignment) {
        return assignment.path().entityType().getName() + "."
                + String.join(".", assignment.path().names());
    }
}
