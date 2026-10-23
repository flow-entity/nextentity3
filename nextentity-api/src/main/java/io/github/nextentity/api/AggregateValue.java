package io.github.nextentity.api;

/// 聚合结果值：`count()` / `countDistinct()` / `max()` / `min()` / `avg()` / `sum()` 的返回类型。
///
/// 它可比较、可区间，但**不接聚合**：本接口不继承 [Aggregates]，
/// 因此 `max(x).max()`、`count(x).sum()` 在编译期就写不出来（不需要运行期抛异常）。
///
/// 也**不接** [Nullness] / [Membership]：`max(x) is null`、`having count(*) in (…)`
/// 需要 `IS NULL` / `IN` 接受表达式主语，本次不提供。
///
/// @param <T> 根实体类型
/// @param <U> 聚合结果类型
public interface AggregateValue<T, U>
        extends Expression<T, U>,
                Comparison<T, U, Predicate<T>>,
                Range<T, U, Predicate<T>>,
                Between<T, U, Predicate<T>> {}
