package io.github.nextentity.core.spec;

import java.util.Objects;

/// 比较节点：`左操作数 算子 右操作数`。
///
/// 两侧都是 [Operand]，因此同一个节点同时覆盖三种写法：
/// - `列 = 字面量`（`left` 是 [AttributePath]，`right` 是 [Literal]）
/// - `列 = 列`（两侧都是 [AttributePath]）
/// - `聚合 > 字面量`（`left` 是 [AggregateOperand]）
///
/// @param left     左操作数
/// @param operator 比较算子
/// @param right    右操作数
public record Comparison(Operand left, ComparisonOperator operator, Operand right) implements Spec {

    /// 紧凑构造器：三字段必填，缺值属于构造期错误而不是渲染期意外。
    public Comparison {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(right, "right");
    }
}
