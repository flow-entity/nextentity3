package io.github.nextentity.api;

/// 聚合投影：把一条属性路径变成可 `select(...)` 的聚合值。
///
/// @param <T> 根实体类型
/// @param <U> 被聚合的属性值类型
public interface Aggregates<T, U> {

    /// 计数。
    ///
    /// @return 计数投影
    NumberExpression<T, Long> count();

    /// 去重计数。
    ///
    /// @return 去重计数投影
    NumberExpression<T, Long> countDistinct();

    /// 最大值。
    ///
    /// @return 最大值投影
    Expression<T, U> max();
}
