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
    /// 与 `Collector#count()` 同口径，因此 `distinct()` 投影上可能比 `data()` 少 1 行
    /// （`COUNT(DISTINCT)` 不计 `NULL`，而 `SELECT DISTINCT` 把 `NULL` 算一组）。
    ///
    /// @return 满足条件的总行数
    long total();

    /// 本页请求的行偏移（0 基），即 `slice(offset, limit)` 的 `offset` 原值。
    ///
    /// @return 请求的偏移；未实现该方法的实现返回 `0`
    default int offset() {
        return 0;
    }

    /// 本页请求的页大小，即 `slice(offset, limit)` 的 `limit` 原值；`0` 表示不限行数。
    ///
    /// @return 请求的页大小；未实现该方法的实现返回 `data().size()`
    default int limit() {
        return data().size();
    }

    /// 把整个分页结果映射成另一种类型（例如消费方自己的 `Page`）。
    ///
    /// @param mapper 映射函数，入参就是本对象，非 `null`
    /// @param <R> 映射结果类型
    /// @return `mapper` 的返回值，原样透传
    <R> R to(Function<Slice<T>, R> mapper);
}
