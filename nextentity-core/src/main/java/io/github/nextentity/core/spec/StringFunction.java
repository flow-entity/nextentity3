package io.github.nextentity.core.spec;

/// 一元字符串函数。
///
/// 与 [BinaryOperator] 分开：这里是"一个主语、一个函数"，没有第二个操作数；
/// 截取带参数，所以单独用 [SubstringOperand] 表达，不塞进本枚举。
public enum StringFunction {

    /// 转小写（`LOWER(x)`）。
    LOWER,

    /// 转大写（`UPPER(x)`）。
    UPPER,

    /// 去首尾空白（`TRIM(x)`）。
    TRIM,

    /// 字符串长度（`LENGTH(x)`，结果是数字）。
    LENGTH
}
