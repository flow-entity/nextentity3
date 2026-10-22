package io.github.nextentity.api;

import java.util.List;

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
        return new PathValues.Root<>(ref);
    }

    /// 由数值方法引用得到数值属性路径（可求和）。
    ///
    /// @param ref 方法引用形式的数值属性
    /// @param <T> 根实体类型
    /// @param <U> 数值类型
    /// @return 数值属性路径
    static <T, U extends Number> NumberPath<T, U> of(PathRef.NumberRef<T, U> ref) {
        return new PathValues.NumberRoot<>(ref);
    }

    /// 属性路径链，从根属性到叶子属性（单段路径返回长度 1 的列表）。
    ///
    /// 这是后端解析属性名的唯一入口（`core.builder` 读它拼 `AttributePath`）。
    ///
    /// @return 不可变路径链
    List<PathRef<?, ?>> refs();
}
