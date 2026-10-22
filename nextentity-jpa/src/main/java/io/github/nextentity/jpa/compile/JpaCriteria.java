package io.github.nextentity.jpa.compile;

import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Projection;
import io.github.nextentity.core.spec.Spec;
import io.github.nextentity.jpa.meta.JpaMeta;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/// 一次编译期的 Criteria 渲染门面：路径（含嵌套 join 缓存）、条件、投影、排序。
///
/// 生命周期 = 一次 `JpaCompiler.compile(...)`；执行器重建计数/存在性查询时也按需新建一份。
/// 分工：{@link JpaPaths} 解析路径，{@link JpaPredicates} 渲染条件，{@link JpaProjections} 渲染投影。
public final class JpaCriteria {

    private final JpaPaths paths;
    private final JpaPredicates predicates;
    private final JpaProjections projections;

    /// 创建渲染器。
    public JpaCriteria(CriteriaBuilder builder, Root<?> root, JpaMeta meta) {
        this.paths = new JpaPaths(builder, root, meta);
        this.predicates = new JpaPredicates(builder, paths);
        this.projections = new JpaProjections(builder, paths);
    }

    /// 渲染属性路径。
    Path<?> path(AttributePath attributePath) {
        return paths.path(attributePath);
    }

    /// 渲染条件。
    public Predicate predicate(Spec spec) {
        return predicates.predicate(spec);
    }

    /// 渲染投影项：投影项只有路径与聚合两种，必为 `Expression`，可参与 `countDistinct`。
    public Expression<?> projection(Projection projection) {
        return projections.projection(projection);
    }

    /// 升序排序项。
    Order ascending(Expression<?> expression) {
        return paths.ascending(expression);
    }

    /// 降序排序项。
    Order descending(Expression<?> expression) {
        return paths.descending(expression);
    }
}
