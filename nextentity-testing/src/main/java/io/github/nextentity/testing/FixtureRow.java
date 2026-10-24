package io.github.nextentity.testing;

/// 固定夹具的一行**原始数据**，后端无关。
///
/// 差分 oracle 的内存一侧直接读它算期望值：期望值必须由夹具数据独立算出，
/// 既不回读后端执行结果，也不调用 nextentity 的任何生产代码，否则就成了「自己证明自己」。
///
/// @param id     主键
/// @param name   名称，非 `null`
/// @param score  数值或 `null`
/// @param score2 第二列数值或 `null`
/// @param note   文本或 `null`
/// @param status 枚举状态，非 `null`
public record FixtureRow(long id, String name, Integer score, Integer score2, String note, ItemStatus status) {}
