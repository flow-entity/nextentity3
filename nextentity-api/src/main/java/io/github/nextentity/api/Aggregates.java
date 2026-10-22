package io.github.nextentity.api;

/// 聚合投影：把一条属性路径变成可 `select(...)` 的聚合值。
///
/// 聚合方法的返回类型统一是 {@link Expression}（而不是 `NumberExpression`）：
/// 聚合结果不允许再接聚合（`count().count()`、`max().max()` 这类写法在类型上就该被拒绝，
/// 否则只能在运行期抛异常）。
///
/// @param <T> 根实体类型
/// @param <U> 被聚合的属性值类型
public interface Aggregates<T, U> {

    /// 计数。
    ///
    /// @return 计数投影
    Expression<T, Long> count();

    /// 去重计数。
    ///
    /// @return 去重计数投影
    Expression<T, Long> countDistinct();

    /// 最大值。
    ///
    /// @return 最大值投影
    Expression<T, U> max();
}
