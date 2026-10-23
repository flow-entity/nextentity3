package io.github.nextentity.api.model;

/// 按位置取列的投影结果。
///
/// `select(first, second)` 的两列投影返回 [Tuple2]（有具名 `get0`/`get1`），`Tuple2` 也是 `Tuple`，
/// 因此"按位置取第 n 列"对 `select(...)` 的所有多列投影都成立。
///
/// `SelectStep#project(ProjectionMapper)` 的映射器路径没有这层具名特化：无论几列，`map` 收到的都是
/// 按位置取值的 `Tuple`，2 列时也是通用实现，不要强转 `Tuple2`。单列在那里同样装配成长度为 1 的
/// `Tuple`（而 `select(...)` 的单列投影直接返回列值，不产生 `Tuple`）。
public interface Tuple {

    /// 取第 `index` 列（0 基）。
    ///
    /// @param index 列下标，取值范围 `[0, size())`
    /// @return 该列的值，可能是 `null`
    Object get(int index);

    /// 列数。
    ///
    /// @return 列数，恒大于等于 1
    int size();
}
