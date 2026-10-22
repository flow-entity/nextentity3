package io.github.nextentity.core.spec;

/// 比较算子：`路径 算子 值` 的单值形态。
///
/// 集合形态（`IN`/`NOT IN`）由 {@link In} 承担，空值判断由 {@link NullCheck} 承担，
/// 闭区间由 {@link Between} 承担——每个算子只表达一种形状，编译器因此不必在分支里判空。
public enum ComparisonOperator {

    /// 等于。
    EQ,

    /// 不等于。
    NE,

    /// 大于。
    GT,

    /// 大于等于。
    GE,

    /// 小于。
    LT,

    /// 小于等于。
    LE,

    /// 模糊匹配（`%v%` 由 builder 拼好，见 `contains`）。
    LIKE
}
