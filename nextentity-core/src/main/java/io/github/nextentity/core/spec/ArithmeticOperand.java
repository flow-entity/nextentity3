package io.github.nextentity.core.spec;

import java.util.Objects;

/// 算术结果作为操作数：`salary * 12`、`(a + b) / 2`。
///
/// @param operator 算术算子
/// @param left     左操作数
/// @param right    右操作数
public record ArithmeticOperand(BinaryOperator operator, Operand left, Operand right) implements Operand {

    /// 紧凑构造器：三字段必填。
    public ArithmeticOperand {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
    }
}
