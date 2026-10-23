package io.github.nextentity.jpa.compile;

import io.github.nextentity.core.spec.AggregateOperand;
import io.github.nextentity.core.spec.ArithmeticOperand;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.Operand;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;

/// `Operand` → Criteria `Expression` 的**唯一**渲染点。
///
/// `Operand` 是 sealed，新增形态会让这里的 `switch` 编译失败（不写 `default`）；
/// 聚合函数与算术算子的分派也只在这里写一份——`JpaProjections` 委托过来，不在别处重抄。
///
/// `Literal` 正常路径不会走到这里：条件里的字面量必须继续走 JPA 的 `Object` 重载以保留
/// 参数绑定与 `@Convert`/时区转换行为（见 {@link JpaPredicates#comparison}）。这里的
/// `Literal` 分支只服务于「字面量真的作为表达式出现」的场合（例如算术右操作数）。
final class JpaOperands {

    private final CriteriaBuilder builder;
    private final JpaPaths paths;

    JpaOperands(CriteriaBuilder builder, JpaPaths paths) {
        this.builder = builder;
        this.paths = paths;
    }

    /// 把操作数渲染成 Criteria 表达式。
    ///
    /// @param operand 操作数
    /// @return Criteria 表达式
    Expression<?> expression(Operand operand) {
        return switch (operand) {
            case Literal literal -> builder.literal(literal.value());
            case AttributePath attributePath -> paths.path(attributePath);
            case AggregateOperand aggregate -> aggregate(aggregate);
            case ArithmeticOperand arithmetic -> arithmetic(arithmetic);
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Expression<?> aggregate(AggregateOperand aggregate) {
        Expression<?> path = paths.path(aggregate.path());
        return switch (aggregate.function()) {
            case MAX -> builder.greatest((Expression) path);
            case MIN -> builder.least((Expression) path);
            case AVG -> builder.avg((Expression) path);
            case SUM -> builder.sum((Expression) path);
            case COUNT -> builder.count(path);
            case COUNT_DISTINCT -> builder.countDistinct(path);
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Expression<?> arithmetic(ArithmeticOperand arithmetic) {
        Expression left = (Expression) expression(arithmetic.left());
        Expression right = (Expression) expression(arithmetic.right());
        return switch (arithmetic.operator()) {
            case ADD -> builder.sum(left, right);
            case SUB -> builder.diff(left, right);
            case MUL -> builder.prod(left, right);
            case DIV -> builder.quot(left, right);
            case MOD -> builder.mod(left, right);
        };
    }
}
