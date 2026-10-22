package io.github.nextentity.api;

import java.util.Collection;

/// 集合成员算子。
///
/// @param <T> 根实体类型
/// @param <U> 属性值类型
/// @param <B> 继续链式的步骤类型
public interface Membership<T, U, B> {

    /// 在给定集合内。
    ///
    /// @param values 候选集合
    /// @return 下一步
    B in(Collection<? extends U> values);

    /// 在给定取值内。
    ///
    /// @param values 候选取值
    /// @return 下一步
    B in(U... values);

    /// 不在给定集合内。
    ///
    /// @param values 排除集合
    /// @return 下一步
    B notIn(Collection<? extends U> values);

    /// 不在给定取值内。
    ///
    /// @param values 排除取值
    /// @return 下一步
    B notIn(U... values);

    /// 在给定集合内，集合为 `null` 时短路。
    ///
    /// @param values 候选集合
    /// @return 下一步
    B inIfNotNull(Collection<? extends U> values);
}
