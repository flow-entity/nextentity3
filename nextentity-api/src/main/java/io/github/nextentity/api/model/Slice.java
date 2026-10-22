package io.github.nextentity.api.model;

import java.util.List;
import java.util.function.Function;

/// 一页数据 + 总数。
///
/// @param <T> 元素类型
public interface Slice<T> {

    /// 当前页数据。
    ///
    /// @return 数据列表
    List<T> data();

    /// 满足条件的总行数。
    ///
    /// @return 总数
    long total();

    /// 把整个分页结果映射成另一种类型（例如消费方自己的 `Page`）。
    ///
    /// @param mapper 映射函数
    /// @param <R> 映射结果类型
    /// @return 映射结果
    <R> R to(Function<Slice<T>, R> mapper);
}
