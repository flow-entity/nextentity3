package io.github.nextentity.api;

import io.github.nextentity.api.model.Slice;

/// 分页入口。
///
/// @param <T> 结果类型
public interface SliceStep<T> {

    /// 取一页数据，同时返回总数。
    ///
    /// @param offset 偏移量
    /// @param limit 每页大小
    /// @return 分页结果
    Slice<T> slice(int offset, int limit);
}
