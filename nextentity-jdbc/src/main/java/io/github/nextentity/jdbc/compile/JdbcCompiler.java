package io.github.nextentity.jdbc.compile;

import io.github.nextentity.core.compile.Compiler;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.jdbc.dialect.JdbcDialect;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import java.util.Objects;

/// `core.compile.Compiler` 的 JDBC 实现：把 `QuerySpec` 渲染成后端可执行的 SQL。
///
/// 方言在构造期固定，只经设计 §6.1 的六个渲染点参与渲染（selectHead / limitClause / lock /
/// quote / averageOf / likeEscape）；其余子句四方言同形。
public final class JdbcCompiler implements Compiler<JdbcQuery> {

    private final JdbcMeta meta;
    private final JdbcDialect dialect;

    /// 构造编译器。
    ///
    /// @param meta    实体元数据
    /// @param dialect 方言，非 null
    public JdbcCompiler(JdbcMeta meta, JdbcDialect dialect) {
        this.meta = Objects.requireNonNull(meta, "meta");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    @Override
    public JdbcQuery compile(QuerySpec spec) {
        QuerySpec valid = requireSpec(spec);
        return new JdbcQuery(valid, new SqlRenderer(meta, dialect, valid).render());
    }

    /// 计数语句，见设计 §3.9。
    ///
    /// @param spec 计数规格（core 已清排序/窗口/锁）
    /// @return 计数 SQL
    public SqlStatement count(QuerySpec spec) {
        return new SqlRenderer(meta, dialect, requireSpec(spec)).count();
    }

    /// 存在性语句，见设计 §3.9。
    ///
    /// @param spec 存在性规格（core 已清排序/窗口/锁）
    /// @return `select 1 ... limit 1`
    public SqlStatement exists(QuerySpec spec) {
        return new SqlRenderer(meta, dialect, requireSpec(spec)).exists();
    }

    private static QuerySpec requireSpec(QuerySpec spec) {
        return Objects.requireNonNull(spec, "spec");
    }
}
