package io.github.nextentity.api;

/// 区间算子。
///
/// @param <T> 根实体类型
/// @param <U> 属性值类型
/// @param <B> 继续链式的步骤类型
public interface Between<T, U, B> {

    /// 闭区间，两端都含。
    ///
    /// @param from 下界，含端点，非 `null`
    /// @param to   上界，含端点，非 `null`
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    B between(U from, U to);
}
