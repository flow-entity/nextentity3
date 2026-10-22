package io.github.nextentity.api;

/// 大小比较算子。
///
/// @param <T> 根实体类型
/// @param <U> 属性值类型
/// @param <B> 继续链式的步骤类型
public interface Range<T, U, B> {

    /// 大于等于。
    ///
    /// @param value 比较值
    /// @return 下一步
    B ge(U value);

    /// 大于。
    ///
    /// @param value 比较值
    /// @return 下一步
    B gt(U value);

    /// 小于等于。
    ///
    /// @param value 比较值
    /// @return 下一步
    B le(U value);

    /// 小于。
    ///
    /// @param value 比较值
    /// @return 下一步
    B lt(U value);
}
