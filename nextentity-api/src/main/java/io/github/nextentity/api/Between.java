package io.github.nextentity.api;

/// 区间算子。
///
/// @param <T> 根实体类型
/// @param <U> 属性值类型
/// @param <B> 继续链式的步骤类型
public interface Between<T, U, B> {

    /// 闭区间。
    ///
    /// @param from 下界
    /// @param to 上界
    /// @return 下一步
    B between(U from, U to);
}
