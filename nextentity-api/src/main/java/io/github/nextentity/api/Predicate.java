package io.github.nextentity.api;

/// 谓词：可继续与其它条件组合的布尔表达式。
///
/// @param <T> 根实体类型
public interface Predicate<T> extends Expression<T, Boolean> {

    /// 与另一个布尔表达式做逻辑与。
    ///
    /// @param other 另一个条件
    /// @return 组合后的谓词
    Predicate<T> and(Expression<T, Boolean> other);

    /// 与另一个布尔表达式做逻辑或。
    ///
    /// @param other 另一个条件
    /// @return 组合后的谓词
    Predicate<T> or(Expression<T, Boolean> other);

    /// 与一条属性路径上的条件做逻辑或。
    ///
    /// @param ref 属性路径
    /// @param <N> 属性值类型
    /// @return 属性算子
    <N> PathOperator<T, N, Predicate<T>> or(PathRef<T, N> ref);
}
