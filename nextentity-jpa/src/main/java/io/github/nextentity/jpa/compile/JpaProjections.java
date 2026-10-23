package io.github.nextentity.jpa.compile;

import io.github.nextentity.core.spec.AggregateOperand;
import io.github.nextentity.core.spec.AggregateProjection;
import io.github.nextentity.core.spec.ComputedProjection;
import io.github.nextentity.core.spec.PathProjection;
import io.github.nextentity.core.spec.Projection;
import jakarta.persistence.criteria.Expression;

/// 投影项 → Criteria 选择项。`Projection` 是 sealed，新增形态会让这里的 switch 编译失败。
///
/// 聚合与算术一律委托 {@link JpaOperands}：聚合函数的 `switch` 全仓库只有那一份。
final class JpaProjections {

    private final JpaPaths paths;
    private final JpaOperands operands;

    JpaProjections(JpaPaths paths, JpaOperands operands) {
        this.paths = paths;
        this.operands = operands;
    }

    Expression<?> projection(Projection projection) {
        return switch (projection) {
            case PathProjection pathProjection -> paths.path(pathProjection.path());
            case AggregateProjection aggregateProjection ->
                operands.expression(new AggregateOperand(aggregateProjection.function(), aggregateProjection.path()));
            case ComputedProjection computedProjection -> operands.expression(computedProjection.operand());
        };
    }
}
