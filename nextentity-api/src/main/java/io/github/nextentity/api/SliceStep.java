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
    /// 返回值的 [Slice#offset()]/[Slice#limit()] 就是本次入参原值（短路路径也一样），
    /// 因此调用方不必再自己记一份请求参数。
    ///
    /// `groupBy(...)` 之后窗口作用在**组**上：`total` 是**组数**（走组计数，`having` 已生效），
    /// `offset >= total` 的空页短路照常。
    ///
    /// @param offset 0 基行偏移，不得为负
    /// @param limit 页大小，不得为负；`0` 表示从 `offset` 起不限行数
    /// @return 一页数据 + 总数 + 本次请求的 `offset`/`limit`
    /// @throws IllegalArgumentException `offset` 或 `limit` 为负数
    /// @throws UnsupportedOperationException 只有 `having` 而没有 `groupBy` 的聚合形态：这种查询恒为
    ///     一行，`total` 没有稳定语义（后端契约，不是本方法自己抛的）
    Slice<T> slice(int offset, int limit);
}
