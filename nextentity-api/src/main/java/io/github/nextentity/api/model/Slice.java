package io.github.nextentity.api.model;

import java.util.List;
import java.util.function.Function;

/// 一页数据 + 总数。
///
/// `total` 与 `data` 来自两条语句（计数查询 + 列表查询），因此值是查询时刻的快照，
/// 并发写入期间不保证与 `data` 严格自洽。
///
/// @param <T> 元素类型
public interface Slice<T> {

    /// 当前页数据。
    ///
    /// @return 当前页数据；与 `total()` 同一时刻的快照，无行时是空列表
    List<T> data();

    /// 满足条件的总行数。
    ///
    /// @return 总行数；`distinct()` 时是去重后的投影值个数（与 `Collector#count()` 同口径）
    long total();

    /// 把整个分页结果映射成另一种类型（例如消费方自己的 `Page`）。
    ///
    /// @param mapper 映射函数，入参就是本对象，非 `null`
    /// @param <R> 映射结果类型
    /// @return `mapper` 的返回值，原样透传
    <R> R to(Function<Slice<T>, R> mapper);
}
