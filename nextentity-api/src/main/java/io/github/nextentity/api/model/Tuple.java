package io.github.nextentity.api.model;

/// 3 列及以上的投影结果：按位置取值。
///
/// 2 列仍返回 [Tuple2]（有具名 `get0`/`get1`）；`Tuple2` 也是 `Tuple`，
/// 因此"按位置取第 n 列"对所有多列投影都成立。
public interface Tuple {

    /// 取第 `index` 列（0 基）。
    ///
    /// @param index 列下标，取值范围 `[0, size())`
    /// @return 该列的值，可能是 `null`
    Object get(int index);

    /// 列数。
    ///
    /// @return 列数，恒大于等于 2
    int size();
}
