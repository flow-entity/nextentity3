package io.github.nextentity.jpa.compile;

import io.github.nextentity.core.spec.AggregateProjection;
import io.github.nextentity.core.spec.PathProjection;
import io.github.nextentity.core.spec.Projection;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;

/// 投影项 → Criteria 选择项。`Projection` 是 sealed，新增形态会让这里的 switch 编译失败。
final class JpaProjections {

    private final CriteriaBuilder builder;
    private final JpaPaths paths;

    JpaProjections(CriteriaBuilder builder, JpaPaths paths) {
        this.builder = builder;
        this.paths = paths;
    }

    Expression<?> projection(Projection projection) {
        return switch (projection) {
            case PathProjection pathProjection -> paths.path(pathProjection.path());
            case AggregateProjection aggregateProjection -> aggregate(aggregateProjection);
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Expression<?> aggregate(AggregateProjection aggregateProjection) {
        Expression expression = (Expression) paths.path(aggregateProjection.path());
        return switch (aggregateProjection.function()) {
            case MAX -> builder.greatest(expression);
            case SUM -> builder.sum(expression);
            case COUNT -> builder.count(expression);
            case COUNT_DISTINCT -> builder.countDistinct(expression);
        };
    }
}
