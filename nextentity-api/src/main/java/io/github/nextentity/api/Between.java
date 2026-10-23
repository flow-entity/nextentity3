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

    /// 闭区间，两端可以是表达式（列对列、聚合对聚合）。
    ///
    /// @param from 下界表达式，非 `null`
    /// @param to   上界表达式，非 `null`
    /// @return 同一条查询的下一步
    B between(Expression<T, U> from, Expression<T, U> to);

    /// 不在区间内（`NOT BETWEEN`）。
    ///
    /// 等价于 `between(from, to).not()`，由后端折成"对区间取反"，不是新算子。
    ///
    /// @param from 下界，含端点，非 `null`
    /// @param to   上界，含端点，非 `null`
    /// @return 同一条查询的下一步
    B notBetween(U from, U to);
}
