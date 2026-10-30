package io.github.nextentity.core.spec;

import java.util.Objects;

/// 一元字符串函数作用于某个操作数的结果：`LOWER(name)`、`LENGTH(TRIM(name))`。
///
/// 主语本身是 [Operand]，因此函数可以嵌套（`lower().trim().contains(...)` = `TRIM(LOWER(name))` 取反的包含）。
///
/// @param function 函数
/// @param subject  主语操作数
public record StringFunctionOperand(StringFunction function, Operand subject) implements Operand {

    /// 紧凑构造器：两字段必填。
    public StringFunctionOperand {
        Objects.requireNonNull(function, "function");
        Objects.requireNonNull(subject, "subject");
    }
}
