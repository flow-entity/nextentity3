package io.github.nextentity.core.builder;

import io.github.nextentity.api.Expression;
import io.github.nextentity.api.Op;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.Predicate;
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
    /// **既不是 `Path` 也不是 `Op` 的 `Expression` 必须拒绝**：`PathRef` 就是这种形态
    /// （`Expression` 但不是 `Path`），当字面量处理会把 lambda 对象本身绑成参数，
    /// 条件恒不成立、查询静默返回 0 行。列对列比较请写 `Path.of(B::getY)`。
    ///
    /// @param argument 表达式或字面量
    /// @return 操作数
    /// @throws IllegalArgumentException 实参是库无法解释的 `Expression`
    static Operand operand(Object argument) {
        if (argument instanceof Path<?, ?> path) {
            return PathRefs.resolve(path.refs());
        }
        if (argument instanceof Op op) {
            return value(op);
        }
        if (argument instanceof Expression<?, ?> expression) {
            throw new IllegalArgumentException("无法解释的表达式: "
                    + expression.getClass().getName() + "（只接受 Path、PathRef 经 Path.of(...) 包装后的路径，或聚合/算术值）");
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
    ///
    /// 顺带消去双重否定（`NOT(NOT(x))` = `x`）：Hibernate 的 `Predicate.not()` 对「最内层是比较谓词」
    /// 的嵌套取反只生效一次，原样下推会得到 `NOT(x)` 的结果（`AndOrPredicateIntegrationTest` 复现）。
    /// 消去是纯语法重写，三值逻辑下等价。
    private static Spec negate(Object argument) {
        if (!(argument instanceof Op op)) {
            return Constant.FALSE;
        }
        Spec spec = toSpec(op);
        if (spec == Constant.TRUE) {
            return Constant.FALSE;
        }
        if (spec == Constant.FALSE) {
            return Constant.TRUE;
        }
        if (spec instanceof Logical logical && logical.operator() == LogicalOperator.NOT) {
            return logical.operands().getFirst();
        }
        return Logical.not(spec);
    }

    /// 逻辑组合：`AND`/`OR` 的子条件放在 `arguments()` 里。
    ///
    /// 子条件里可能混入 api 的"被短路条件"（`Conditions.AlwaysTrue`，是 `Predicate` 但不实现 `Op`）：
    /// - `AND` 下它是恒真，跳过不影响结果；
    /// - `OR` 下它是恒真，**整条 OR 恒真**，必须返回 `Constant.TRUE`（跳过会算错）。
    ///
    /// 判据是 `Predicate` 而**不是**"不是 `Op`"：`Path<T, Boolean>` 同样不是 `Op`，但它是
    /// "把整列当条件"的写法（2.2.3 也不支持，见 `docs/USAGE.md`「布尔字段请写 `.eq(true)`」）。
    /// 旧实现把这类实参一律当占位，于是 `pred.or(Path.of(Order::getActive))` 返回恒真、
    /// WHERE 整体消失（连租户边界一起），是静默 fail-open。现在当场拒绝。
    ///
    /// 过滤后一个都不剩时：`AND` 恒真、`OR` 恒假（`or(空集合)` 不能匹配全表）。
    private static Spec logical(LogicalOperator operator, List<Object> arguments) {
        List<Spec> operands = new ArrayList<>(arguments.size());
        for (Object argument : arguments) {
            if (argument instanceof Op op) {
                operands.add(toSpec(op));
            } else if (!isPlaceholder(argument)) {
                throw notCondition(argument, "and/or 的子条件");
            } else if (operator == LogicalOperator.OR) {
                return Constant.TRUE;
            }
        }
        if (operands.isEmpty()) {
            return operator == LogicalOperator.OR ? Constant.FALSE : Constant.TRUE;
        }
        return new Logical(operator, operands);
    }

    /// 是否为 api 的"被短路条件"占位（`Conditions.AlwaysTrue`）：`Predicate` 但不实现 `Op`。
    ///
    /// 这是 `where` / `having` / `and` / `or` 唯一允许的非 `Op` 入参；其余非 `Op` 表达式
    /// （典型是 `Path<T, Boolean>`）一律按 [Expression] 走 [ #notCondition]。
    static boolean isPlaceholder(Object argument) {
        return argument instanceof Predicate<?>;
    }

    /// 非条件表达式被送进条件位置的统一拒绝消息。
    ///
    /// @param argument 实参
    /// @param position 位置描述，例如 `where(...)` / `and/or 的子条件`
    /// @return 带类型与正确写法的异常
    static IllegalArgumentException notCondition(Object argument, String position) {
        String type = argument == null ? "null" : argument.getClass().getName();
        return new IllegalArgumentException(
                position + " 只接受条件表达式（api 的 Op）：" + "布尔字段请写 .eq(true)，不支持把字段本身当条件；实际 " + type);
    }
}
