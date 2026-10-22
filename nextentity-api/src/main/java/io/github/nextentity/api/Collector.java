package io.github.nextentity.api;

import java.util.List;

/// 终止操作：把查询真正执行掉。
///
/// @param <T> 结果类型
public interface Collector<T> {

    /// 取全部结果。
    ///
    /// @return 结果列表
    List<T> list();

    /// 取前 N 条。
    ///
    /// @param limit 条数上限
    /// @return 结果列表
    List<T> list(int limit);

    /// 取第一条，无结果返回 `null`。
    ///
    /// @return 第一条或 `null`
    T first();

    /// 取唯一一条，无结果返回 `null`。
    ///
    /// @return 唯一一条或 `null`
    /// @throws IllegalStateException 命中多于一条（消息固定 `found more than one result, expected one`）
    T single();

    /// 计数。
    ///
    /// @return 行数
    long count();

    /// 是否存在。
    ///
    /// @return 存在返回 `true`
    boolean exists();
}
