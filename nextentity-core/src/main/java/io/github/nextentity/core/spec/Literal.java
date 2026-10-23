package io.github.nextentity.core.spec;

import java.util.Objects;

/// 字面量：比较值、区间端点、算术右操作数。
///
/// 名字刻意不叫 `Constant`——[Constant] 已被"恒真/恒假"节点占用。
///
/// @param value 字面量值，非 null
public record Literal(Object value) implements Operand {

    /// 紧凑构造器：字面量不能是 null（"值为 null 就不加条件"由 builder 短路，不落到 AST）。
    public Literal {
        Objects.requireNonNull(value, "value");
    }

    /// 构造字面量。
    ///
    /// @param value 值，非 null
    /// @return 字面量节点
    public static Literal of(Object value) {
        return new Literal(value);
    }
}
