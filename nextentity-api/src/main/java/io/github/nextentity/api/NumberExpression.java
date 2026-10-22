package io.github.nextentity.api;

/// 数值聚合表达式。
///
/// @param <T> 根实体类型
/// @param <U> 数值类型
public interface NumberExpression<T, U extends Number> extends Expression<T, U>, Aggregates<T, U> {

    /// 求和。
    ///
    /// @return 求和投影
    NumberExpression<T, U> sum();

    @Override
    NumberExpression<T, U> max();
}
