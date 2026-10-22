package io.github.nextentity.jpa.compile;

import io.github.nextentity.core.spec.Between;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.Constant;
import io.github.nextentity.core.spec.In;
import io.github.nextentity.core.spec.Logical;
import io.github.nextentity.core.spec.LogicalOperator;
import io.github.nextentity.core.spec.NullCheck;
import io.github.nextentity.core.spec.Spec;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;

/// 条件节点 → Criteria 条件。`Spec` 是 sealed，新增节点会让这里的 switch 编译失败。
final class JpaPredicates {

    private final CriteriaBuilder builder;
    private final JpaPaths paths;

    JpaPredicates(CriteriaBuilder builder, JpaPaths paths) {
        this.builder = builder;
        this.paths = paths;
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Predicate comparison(Comparison comparison) {
        Path<?> path = paths.path(comparison.path());
        Object value = comparison.value();
        return switch (comparison.operator()) {
            case EQ -> builder.equal(path, value);
            case NE -> builder.notEqual(path, value);
            case GT -> builder.greaterThan((Expression) path, (Comparable) value);
            case GE -> builder.greaterThanOrEqualTo((Expression) path, (Comparable) value);
            case LT -> builder.lessThan((Expression) path, (Comparable) value);
            case LE -> builder.lessThanOrEqualTo((Expression) path, (Comparable) value);
            case LIKE -> builder.like((Expression) path, (String) value);
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Predicate between(Between between) {
        return builder.between(
                (Expression) paths.path(between.path()), (Comparable) between.lower(), (Comparable) between.upper());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Predicate in(In in) {
        CriteriaBuilder.In<Object> criteriaIn = builder.in((Expression) paths.path(in.path()));
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
