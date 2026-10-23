package io.github.nextentity.core.builder;

import io.github.nextentity.api.Op;
import io.github.nextentity.api.Path;
import io.github.nextentity.core.spec.AggregateFunction;
import io.github.nextentity.core.spec.AggregateOperand;
import io.github.nextentity.core.spec.AggregateProjection;
import io.github.nextentity.core.spec.ArithmeticOperand;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.BinaryOperator;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.ComputedProjection;
import io.github.nextentity.core.spec.Constant;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.Logical;
import io.github.nextentity.core.spec.LogicalOperator;
import io.github.nextentity.core.spec.Operand;
import io.github.nextentity.core.spec.Projection;
import io.github.nextentity.core.spec.Spec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/// `api.Op` → `core.spec` 节点的解释器。
///
/// `api` 只承载"用户写下的表达式"（路径链 + 运算 + 参数 + 左侧主体），不做任何语义决策；
/// 求值语义全部落在 `core.spec`，因此这里每个分支都只是"把运算换成节点"。
///
/// 两个 `switch` 都必须穷尽且**不写 `default`**：api 新增 `Operation` 常量时，
/// 这里必须编译失败，逼着把新语义补齐（AGENTS「新节点必须让穷尽性检查报错」）。
@SuppressWarnings("PMD.CyclomaticComplexity") // 27 个 Operation 常量必须在一个穷尽 switch 里翻译，分支数由 api 面决定，拆开就破坏穷尽性门禁
final class OpInterpreter {

    private OpInterpreter() {}

    /// 把条件值翻译成条件节点。
    ///
    /// @param op 条件值
    /// @return 条件节点
    static Spec toSpec(Op op) {
        List<Object> arguments = op.arguments();
        return switch (op.operation()) {
            case EQ -> new Comparison(left(op), ComparisonOperator.EQ, operand(arguments.getFirst()));
            case NE -> new Comparison(left(op), ComparisonOperator.NE, operand(arguments.getFirst()));
            case GE -> new Comparison(left(op), ComparisonOperator.GE, operand(arguments.getFirst()));
            case GT -> new Comparison(left(op), ComparisonOperator.GT, operand(arguments.getFirst()));
            case LE -> new Comparison(left(op), ComparisonOperator.LE, operand(arguments.getFirst()));
            case LT -> new Comparison(left(op), ComparisonOperator.LT, operand(arguments.getFirst()));
            case LIKE -> Specs.like(left(op), (String) arguments.getFirst());
            case CONTAINS -> Specs.contains(left(op), arguments.getFirst());
            case BETWEEN -> Specs.between(left(op), operand(arguments.get(0)), operand(arguments.get(1)));
            case IN -> Specs.membership(attribute(op), (Collection<?>) arguments.getFirst(), false);
            case NOT_IN -> Specs.membership(attribute(op), (Collection<?>) arguments.getFirst(), true);
            case IS_NULL -> Specs.nullCheck(attribute(op), false);
            case IS_NOT_NULL -> Specs.nullCheck(attribute(op), true);
            case AND -> logical(LogicalOperator.AND, arguments);
            case OR -> logical(LogicalOperator.OR, arguments);
            case NOT -> negate(arguments.getFirst());
            // 运行期不可达：where/having 只接受 Expression<T,Boolean>，聚合值与算术值进不来。
            // 这些常量必须列出，是 switch 穷尽性检查的结果，不是兜底分支。
            case COUNT, COUNT_DISTINCT, MAX, MIN, AVG, SUM, ADD, SUB, MUL, DIV, MOD ->
                throw new IllegalArgumentException("不是条件运算: " + op.operation());
        };
    }

    /// 把表达式翻译成投影项。
    ///
    /// @param op 表达式
    /// @return 投影项
    static Projection toProjection(Op op) {
        return switch (op.operation()) {
            case COUNT -> new AggregateProjection(AggregateFunction.COUNT, PathRefs.resolve(op.refs()));
            case COUNT_DISTINCT ->
                new AggregateProjection(AggregateFunction.COUNT_DISTINCT, PathRefs.resolve(op.refs()));
            case MAX -> new AggregateProjection(AggregateFunction.MAX, PathRefs.resolve(op.refs()));
            case MIN -> new AggregateProjection(AggregateFunction.MIN, PathRefs.resolve(op.refs()));
            case AVG -> new AggregateProjection(AggregateFunction.AVG, PathRefs.resolve(op.refs()));
            case SUM -> new AggregateProjection(AggregateFunction.SUM, PathRefs.resolve(op.refs()));
            case ADD, SUB, MUL, DIV, MOD -> new ComputedProjection(value(op));
            case EQ, NE, GE, GT, LE, LT, IN, NOT_IN, IS_NULL, IS_NOT_NULL, BETWEEN, CONTAINS, LIKE, AND, OR, NOT ->
                throw new IllegalArgumentException("不是投影运算: " + op.operation());
        };
    }

    /// 把表达式对象转成操作数：`Path` 取路径链，`Op` 递归取值，其余当字面量。
    ///
    /// @param argument 表达式或字面量
    /// @return 操作数
    static Operand operand(Object argument) {
        if (argument instanceof Path<?, ?> path) {
            return PathRefs.resolve(path.refs());
        }
        if (argument instanceof Op op) {
            return value(op);
        }
        return Literal.of(argument);
    }

    /// 条件的主语：`subject()` 非空时用它，否则用 `refs()` 解析出的属性路径。
    private static Operand left(Op op) {
        return op.subject() == null ? PathRefs.resolve(op.refs()) : value(op.subject());
    }

    /// `IN` / `IS NULL` 的主语必须是属性路径（api 没有为它们开放表达式主语）。
    private static AttributePath attribute(Op op) {
        return PathRefs.resolve(op.refs());
    }

    /// 聚合值 / 算术值 → 操作数节点。
    private static Operand value(Op op) {
        return switch (op.operation()) {
            case MAX -> new AggregateOperand(AggregateFunction.MAX, PathRefs.resolve(op.refs()));
            case MIN -> new AggregateOperand(AggregateFunction.MIN, PathRefs.resolve(op.refs()));
            case AVG -> new AggregateOperand(AggregateFunction.AVG, PathRefs.resolve(op.refs()));
            case SUM -> new AggregateOperand(AggregateFunction.SUM, PathRefs.resolve(op.refs()));
            case COUNT -> new AggregateOperand(AggregateFunction.COUNT, PathRefs.resolve(op.refs()));
            case COUNT_DISTINCT -> new AggregateOperand(AggregateFunction.COUNT_DISTINCT, PathRefs.resolve(op.refs()));
            case ADD -> arithmetic(BinaryOperator.ADD, op);
            case SUB -> arithmetic(BinaryOperator.SUB, op);
            case MUL -> arithmetic(BinaryOperator.MUL, op);
            case DIV -> arithmetic(BinaryOperator.DIV, op);
            case MOD -> arithmetic(BinaryOperator.MOD, op);
            case EQ, NE, GE, GT, LE, LT, IN, NOT_IN, IS_NULL, IS_NOT_NULL, BETWEEN, CONTAINS, LIKE, AND, OR, NOT ->
                throw new IllegalArgumentException("不是值表达式: " + op.operation());
        };
    }

    private static Operand arithmetic(BinaryOperator operator, Op op) {
        return new ArithmeticOperand(operator, left(op), operand(op.arguments().getFirst()));
    }

    /// 逻辑取反：非 `Op` 实参是"恒真占位"，取反即恒假。
    private static Spec negate(Object argument) {
        return argument instanceof Op op ? Logical.not(toSpec(op)) : Constant.FALSE;
    }

    /// 逻辑组合：`AND`/`OR` 的子条件放在 `arguments()` 里。
    ///
    /// 子条件里可能混入 api 的"被短路条件"（`Conditions.AlwaysTrue`，不实现 `Op`）：
    /// - `AND` 下它是恒真，跳过不影响结果；
    /// - `OR` 下它是恒真，**整条 OR 恒真**，必须返回 `Constant.TRUE`（跳过会算错）。
    ///
    /// 过滤后一个都不剩时：`AND` 恒真、`OR` 恒假（`or(空集合)` 不能匹配全表）。
    private static Spec logical(LogicalOperator operator, List<Object> arguments) {
        List<Spec> operands = new ArrayList<>(arguments.size());
        for (Object argument : arguments) {
            if (argument instanceof Op op) {
                operands.add(toSpec(op));
            } else if (operator == LogicalOperator.OR) {
                return Constant.TRUE;
            }
        }
        if (operands.isEmpty()) {
            return operator == LogicalOperator.OR ? Constant.FALSE : Constant.TRUE;
        }
        return new Logical(operator, operands);
    }
}
