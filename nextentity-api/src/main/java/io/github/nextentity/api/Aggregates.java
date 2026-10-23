package io.github.nextentity.api;

/// 聚合投影：把一条属性路径变成可 `select(...)` 的聚合值。
///
/// 聚合方法的返回类型统一是 [AggregateValue]：它可比较、可区间，但**不接聚合**，
/// 因此 `count().count()`、`max().max()` 这类写法在类型上就被拒绝（不需要运行期抛异常）。
///
/// `avg()` 不在这里，而在 [NumberPath] 上：对非数值列求平均是渲染期才炸的错误
/// （`AVG(varchar)`），用类型系统挡掉比用运行期异常好。`min()` / `max()` 留在本接口：
/// `max(createdAt)` 这类非数值列的极值是合法的。
///
/// @param <T> 根实体类型
/// @param <U> 被聚合的属性值类型
public interface Aggregates<T, U> {

    /// 计数。
    ///
    /// @return `COUNT(路径)` 表达式；无行时结果为 `0`（不是 `null`）
    AggregateValue<T, Long> count();

    /// 去重计数。
    ///
    /// @return `COUNT(DISTINCT 路径)` 表达式；`null` 不计入，因此可能小于 `count()`
    AggregateValue<T, Long> countDistinct();

    /// 最大值。
    ///
    /// @return `MAX(路径)` 表达式；无行时结果为 `null`
    AggregateValue<T, U> max();

    /// 最小值。
    ///
    /// @return `MIN(路径)` 表达式；无行时结果为 `null`
    AggregateValue<T, U> min();
}
