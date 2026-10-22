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
    /// @return `COUNT(路径)` 表达式；无行时结果为 `0`（不是 `null`）
    Expression<T, Long> count();

    /// 去重计数。
    ///
    /// @return `COUNT(DISTINCT 路径)` 表达式；`null` 不计入，因此可能小于 `count()`
    Expression<T, Long> countDistinct();

    /// 最大值。
    ///
    /// @return `MAX(路径)` 表达式；无行时结果为 `null`
    Expression<T, U> max();
}
