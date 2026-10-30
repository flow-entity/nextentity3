package io.github.nextentity.core.spec;

/// 计数/存在性针对什么：整个查询的行，还是分组结果的行（组）。
///
/// 单独成枚举而不是塞进 [QuerySpec#aggregate]：带 `groupBy` 的查询有两种"数"，答案不同——
/// `count()` 数的是**组数**，`select(count())` 数的是**每组内的行数**。后端必须能分辨，
/// 否则要么静默给错数，要么只能整体拒绝分组查询的计数。
public enum CountScope {

    /// 数满足条件的行（普通查询）。
    ROWS,

    /// 数分组结果的组（`groupBy(...)` 之后，`HAVING` 已生效）。
    GROUPS
}
