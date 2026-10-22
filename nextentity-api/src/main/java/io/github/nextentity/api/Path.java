package io.github.nextentity.api;

/// 属性路径：`select(...)` 与聚合的投影对象，也是条件算子的载体。
///
/// @param <T> 根实体类型
/// @param <U> 属性值类型
public interface Path<T, U>
        extends Expression<T, U>,
                PathRef<T, U>,
                Comparison<T, U, Predicate<T>>,
                Range<T, U, Predicate<T>>,
                RangeIfNotNull<T, U, Predicate<T>>,
                Nullness<Predicate<T>>,
                Membership<T, U, Predicate<T>>,
                Between<T, U, Predicate<T>>,
                Aggregates<T, U> {

    /// 由方法引用得到属性路径。
    ///
    /// @param ref 方法引用形式的属性
    /// @param <T> 根实体类型
    /// @param <U> 属性值类型
    /// @return 属性路径
    static <T, U> Path<T, U> of(PathRef<T, U> ref) {
        return Paths.factory().path(ref);
    }

    /// 由数值方法引用得到数值属性路径（可求和）。
    ///
    /// @param ref 方法引用形式的数值属性
    /// @param <T> 根实体类型
    /// @param <U> 数值类型
    /// @return 数值属性路径
    static <T, U extends Number> NumberPath<T, U> of(PathRef.NumberRef<T, U> ref) {
        return Paths.factory().number(ref);
    }
}
