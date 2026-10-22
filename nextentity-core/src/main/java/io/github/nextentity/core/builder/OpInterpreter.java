package io.github.nextentity.core.builder;

import io.github.nextentity.api.Op;
import io.github.nextentity.core.spec.AggregateFunction;
import io.github.nextentity.core.spec.AggregateProjection;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.Constant;
import io.github.nextentity.core.spec.Logical;
import io.github.nextentity.core.spec.LogicalOperator;
import io.github.nextentity.core.spec.Projection;
import io.github.nextentity.core.spec.Spec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/// `api.Op` → `core.spec` 节点的解释器。
///
/// `api` 只承载"用户写下的表达式"（路径链 + 运算 + 参数），不做任何语义决策；
/// 求值语义全部落在 `core.spec`，因此这里每个分支都只是"把运算换成节点"。
///
/// `switch (op.operation())` 必须穷尽且**不写 `default`**：api 新增 `Operation` 常量时，
/// 这里必须编译失败，逼着把新语义补齐（AGENTS「新节点必须让穷尽性检查报错」）。
@SuppressWarnings("PMD.CyclomaticComplexity") // 19 个 Operation 常量必须在一个穷尽 switch 里翻译，分支数由 api 面决定，拆开就破坏穷尽性门禁
final class OpInterpreter {

    private OpInterpreter() {}

    /// 把条件值翻译成条件节点。
    ///
    /// @param op 条件值，非 `null`；聚合运算进来抛 `IllegalArgumentException`
    /// @return 条件节点
    static Spec toSpec(Op op) {
        AttributePath path = op.refs().isEmpty() ? null : PathRefs.resolve(op.refs());
        List<Object> arguments = op.arguments();
        return switch (op.operation()) {
            case EQ -> Specs.comparison(path, ComparisonOperator.EQ, arguments.getFirst());
            case NE -> Specs.comparison(path, ComparisonOperator.NE, arguments.getFirst());
            case GE -> Specs.comparison(path, ComparisonOperator.GE, arguments.getFirst());
            case GT -> Specs.comparison(path, ComparisonOperator.GT, arguments.getFirst());
            case LE -> Specs.comparison(path, ComparisonOperator.LE, arguments.getFirst());
            case LT -> Specs.comparison(path, ComparisonOperator.LT, arguments.getFirst());
            case LIKE -> Specs.like(path, (String) arguments.getFirst());
            case CONTAINS -> Specs.contains(path, arguments.getFirst());
            case BETWEEN -> Specs.between(path, arguments.get(0), arguments.get(1));
            case IN -> Specs.membership(path, (Collection<?>) arguments.getFirst(), false);
            case NOT_IN -> Specs.membership(path, (Collection<?>) arguments.getFirst(), true);
            case IS_NULL -> Specs.nullCheck(path, false);
            case IS_NOT_NULL -> Specs.nullCheck(path, true);
            case AND -> logical(LogicalOperator.AND, arguments);
            case OR -> logical(LogicalOperator.OR, arguments);
            // 运行期不可达：where(...) 只接受 Expression<T,Boolean>，聚合值进不来。
            // 这四个常量必须列出，是 switch 穷尽性检查的结果，不是兜底分支。
            case COUNT, COUNT_DISTINCT, MAX, SUM ->
                throw new IllegalArgumentException("聚合运算不能当作条件使用: " + op.operation());
        };
    }

    /// 把聚合表达式翻译成投影项。
    ///
    /// 只有四种聚合运算能出现在 `select(...)` 里；其余运算一律拒绝（不是"兜底返回"，
    /// 而是把非法输入挡在构造期）。
    ///
    /// @param op 聚合表达式，非 `null`；非聚合运算抛 `IllegalArgumentException`
    /// @return 聚合投影项
    static Projection toProjection(Op op) {
        return switch (op.operation()) {
            case COUNT -> new AggregateProjection(AggregateFunction.COUNT, PathRefs.resolve(op.refs()));
            case COUNT_DISTINCT ->
                new AggregateProjection(AggregateFunction.COUNT_DISTINCT, PathRefs.resolve(op.refs()));
            case MAX -> new AggregateProjection(AggregateFunction.MAX, PathRefs.resolve(op.refs()));
            case SUM -> new AggregateProjection(AggregateFunction.SUM, PathRefs.resolve(op.refs()));
            case EQ, NE, GE, GT, LE, LT, IN, NOT_IN, IS_NULL, IS_NOT_NULL, BETWEEN, CONTAINS, LIKE, AND, OR ->
                throw new IllegalArgumentException("不是聚合投影: " + op.operation());
        };
    }

    /// 逻辑组合：`AND`/`OR` 的子条件放在 `arguments()` 里。
    ///
    /// 子条件里可能混入 api 的"被短路条件"（`Conditions.AlwaysTrue`，不实现 `Op`）——
    /// api 约定它表示"无附加条件"，因此这里直接跳过。
    ///
    /// 一个都不剩时返回 `Constant.TRUE` 不是兜底：那是真实语义，
    /// 例如 `eqIfNotNull(null).or(eqIfNotNull(null))` 就是 `TRUE OR TRUE`，恒真。
    private static Spec logical(LogicalOperator operator, List<Object> arguments) {
        List<Spec> operands = new ArrayList<>(arguments.size());
        for (Object argument : arguments) {
            if (argument instanceof Op op) {
                operands.add(toSpec(op));
            }
        }
        if (operands.isEmpty()) {
            return Constant.TRUE;
        }
        return new Logical(operator, operands);
    }
}
