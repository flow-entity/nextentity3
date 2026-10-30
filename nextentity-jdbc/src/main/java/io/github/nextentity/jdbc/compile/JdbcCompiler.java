package io.github.nextentity.jdbc.compile;

import io.github.nextentity.core.compile.Compiler;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.jdbc.dialect.JdbcDialect;
import io.github.nextentity.jdbc.meta.JdbcEntityAttribute;
import io.github.nextentity.jdbc.meta.JdbcEntityType;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// `core.compile.Compiler` 的 JDBC 实现：把 `QuerySpec` 渲染成后端可执行的 SQL。
///
/// 方言在构造期固定，只经设计 §6.1 的方言钩子参与渲染（selectHead / limitClause / lock /
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
        rejectFetch(valid);
        return new JdbcQuery(valid, new SqlRenderer(meta, dialect, valid).render());
    }

    /// 计数语句。
    ///
    /// @param spec 计数规格（core 已清排序/窗口/锁）
    /// @return 计数 SQL
    public SqlStatement count(QuerySpec spec) {
        return new SqlRenderer(meta, dialect, requireSpec(spec)).count();
    }

    /// 存在性语句。
    ///
    /// @param spec 存在性规格（core 已清排序/窗口/锁）
    /// @return `select 1 ... limit 1`
    public SqlStatement exists(QuerySpec spec) {
        return new SqlRenderer(meta, dialect, requireSpec(spec)).exists();
    }

    private static QuerySpec requireSpec(QuerySpec spec) {
        return Objects.requireNonNull(spec, "spec");
    }

    /// JDBC 后端不组装实体图、也没有懒加载代理，`fetch` 无法表达；拒绝而不是静默忽略——静默会让关联字段
    /// 保持 `null`，调用方却以为预加载成功。单值关联与集合关联分开列出，方便调用方判断要改哪个调用。
    ///
    /// @throws UnsupportedOperationException 规格带 `fetch` 时
    private void rejectFetch(QuerySpec spec) {
        if (spec.fetch().isEmpty()) {
            return;
        }
        JdbcEntityType<?> entityType = meta.entityType(spec.entityType());
        List<String> single = new ArrayList<>();
        List<String> collections = new ArrayList<>();
        for (AttributePath path : spec.fetch()) {
            (isCollection(entityType, path) ? collections : single).add(describe(path));
        }
        StringBuilder detail = new StringBuilder();
        if (!single.isEmpty()) {
            detail.append("单值关联 ").append(single);
        }
        if (!collections.isEmpty()) {
            if (!detail.isEmpty()) {
                detail.append("、");
            }
            detail.append("集合关联 ").append(collections);
        }
        throw new UnsupportedOperationException("JDBC 后端不支持 fetch(预加载关联): "
                + spec.entityType().getName() + " " + detail
                + "；请改用 project(ProjectionMapper) 取关联列，或改用 JPA 后端");
    }

    /// 集合关联在元数据里表现为「关联但无 `@JoinColumn`」（Tooltip: 集合关联被识别却忽略，见 USAGE §13.4）。
    private static boolean isCollection(JdbcEntityType<?> entityType, AttributePath path) {
        JdbcEntityAttribute attribute = entityType.attribute(path.names().getFirst());
        return attribute != null && attribute.isAssociation() && attribute.joinColumn() == null;
    }

    private static String describe(AttributePath path) {
        return String.join(".", path.names());
    }
}
