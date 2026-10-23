package io.github.nextentity.core.spec;

/// 算术二元算子。
///
/// 与 [ComparisonOperator] 分开：一个是"算术"，一个是"比较"，混在一个枚举里会让编译器的
/// `switch` 分支出两种语义完全不同的情况。
public enum BinaryOperator {

    /// 加。
    ADD,

    /// 减。
    SUB,

    /// 乘。
    MUL,

    /// 除。
    DIV,

    /// 取模。
    MOD
}
