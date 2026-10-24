package io.github.nextentity.api;

import io.github.nextentity.api.model.Slice;

/// 分页入口。
///
/// @param <T> 结果类型
public interface SliceStep<T> {

    /// 取一页数据，同时返回总数。
    ///
    /// `total` 来自同一条件的计数查询，与当前页无关；`offset` 不小于 `total` 时返回空 `data`，
    /// 并且不再执行列表查询（证据：`core/builder/QueryBuilder.java:231-241`）。没有显式 `orderBy` 时
    /// 列表查询按主键升序（`distinct()` 投影除外，要稳定分页请显式 `orderBy`）。
    ///
    /// @param offset 0 基行偏移，不得为负
    /// @param limit 页大小，不得为负；`0` 表示从 `offset` 起不限行数
    /// @return 一页数据 + 总数
    /// @throws IllegalArgumentException `offset` 或 `limit` 为负数
    /// @throws UnsupportedOperationException 查询带 `groupBy` / `having` 或聚合投影：总数依赖
    ///     `count()`，而它不支持这类查询
    Slice<T> slice(int offset, int limit);
}
