package io.github.nextentity.api;

/// 算术结果值：`列 + 值`、`列 * 值` 这类表达式的类型。
///
/// 可继续算术（继承 [NumberArithmetic]），也可比较、可区间——因此
/// `where(X::getSalary).add(1000).gt(50000)` 与 `select(X::getSalary).mul(12)` 都成立。
///
/// 不接聚合：`sum(salary * 12)` 这种"聚合套算术"本次不提供（聚合结果也不能再聚合）。
///
/// @param <T> 根实体类型
/// @param <U> 数值类型
public interface NumberValue<T, U extends Number>
        extends Expression<T, U>,
                Comparison<T, U, Predicate<T>>,
                Range<T, U, Predicate<T>>,
                Between<T, U, Predicate<T>>,
                NumberArithmetic<T, U> {}
