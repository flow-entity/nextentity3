package io.github.nextentity.jpa.compile;

import io.github.nextentity.core.spec.Between;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.Constant;
import io.github.nextentity.core.spec.In;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.Logical;
import io.github.nextentity.core.spec.LogicalOperator;
import io.github.nextentity.core.spec.NullCheck;
import io.github.nextentity.core.spec.Operand;
import io.github.nextentity.core.spec.Spec;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;

/// 条件节点 → Criteria 条件。`Spec` 是 sealed，新增节点会让这里的 switch 编译失败。
///
/// 右值是 {@link Literal} 时必须走 JPA 的 `Object` 重载（`builder.equal(left, value)` 等）：
/// 那条路径保留参数绑定与 `@Convert`/时区转换行为；只有右值真的是表达式（列、聚合、算术）时
/// 才渲染成 `Expression` 走表达式重载。
final class JpaPredicates {

    private final CriteriaBuilder builder;
    private final JpaPaths paths;
    private final JpaOperands operands;

    JpaPredicates(CriteriaBuilder builder, JpaPaths paths, JpaOperands operands) {
        this.builder = builder;
        this.paths = paths;
        this.operands = operands;
    }

    Predicate predicate(Spec spec) {
        return switch (spec) {
            case Comparison comparison -> comparison(comparison);
            case Between between -> between(between);
            case In in -> in(in);
            case NullCheck nullCheck -> nullCheck(nullCheck);
            case Logical logical -> logical(logical);
            case Constant constant -> constant.value() ? builder.conjunction() : builder.disjunction();
        };
    }

    private Predicate comparison(Comparison comparison) {
        Expression<?> left = operands.expression(comparison.left());
        Operand right = comparison.right();
        return right instanceof Literal(Object value)
                ? withValue(comparison.operator(), left, value)
                : withExpression(comparison.operator(), left, operands.expression(right));
    }

    /// 右值是字面量：走 JPA 的 `Object` 重载，保留参数绑定与 `@Convert`/时区转换行为。
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Predicate withValue(ComparisonOperator operator, Expression<?> left, Object value) {
        Expression expression = left;
        return switch (operator) {
            case EQ -> builder.equal(expression, value);
            case NE -> builder.notEqual(expression, value);
            case GT -> builder.greaterThan(expression, (Comparable) value);
            case GE -> builder.greaterThanOrEqualTo(expression, (Comparable) value);
            case LT -> builder.lessThan(expression, (Comparable) value);
            case LE -> builder.lessThanOrEqualTo(expression, (Comparable) value);
            case LIKE -> builder.like(expression, (String) value);
        };
    }

    /// 右值是表达式（列 / 聚合 / 算术）：走 JPA 的 `Expression` 重载。
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Predicate withExpression(ComparisonOperator operator, Expression<?> left, Expression<?> right) {
        Expression expression = left;
        Expression other = right;
        return switch (operator) {
            case EQ -> builder.equal(expression, other);
            case NE -> builder.notEqual(expression, other);
            case GT -> builder.greaterThan(expression, other);
            case GE -> builder.greaterThanOrEqualTo(expression, other);
            case LT -> builder.lessThan(expression, other);
            case LE -> builder.lessThanOrEqualTo(expression, other);
            // LIKE 的模式串只能是字面量（builder 侧 CONTAINS 会先拼成 %v%），表达式形态没有渲染语义
            case LIKE -> throw new IllegalArgumentException("LIKE 的右侧必须是字面量，不能是表达式");
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Predicate between(Between between) {
        Expression subject = operands.expression(between.subject());
        Operand lower = between.lower();
        Operand upper = between.upper();
        if (lower instanceof Literal(Object lowerValue) && upper instanceof Literal(Object upperValue)) {
            return builder.between(subject, (Comparable) lowerValue, (Comparable) upperValue);
        }
        return builder.between(
                subject, (Expression) operands.expression(lower), (Expression) operands.expression(upper));
    }

    private Predicate in(In in) {
        CriteriaBuilder.In<Object> criteriaIn = builder.in(paths.path(in.path()));
        for (Object value : in.values()) {
            criteriaIn.value(value);
        }
        return in.negated() ? criteriaIn.not() : criteriaIn;
    }

    private Predicate nullCheck(NullCheck nullCheck) {
        Path<?> path = paths.path(nullCheck.path());
        return nullCheck.negated() ? builder.isNotNull(path) : builder.isNull(path);
    }

    private Predicate logical(Logical logical) {
        if (logical.operator() == LogicalOperator.NOT) {
            return predicate(logical.operands().getFirst()).not();
        }
        Predicate[] predicates =
                logical.operands().stream().map(this::predicate).toArray(Predicate[]::new);
        return logical.operator() == LogicalOperator.AND ? builder.and(predicates) : builder.or(predicates);
    }
}
