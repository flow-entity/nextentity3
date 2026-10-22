package io.github.nextentity.api;

import java.util.List;

/// 属性路径：`select(...)` 与聚合的投影对象，也是条件算子的载体。
///
/// **注意：`Path` 不继承 `PathRef`**。`PathRef` 是用户写的**方法引用**（lambda），后端靠
/// `SerializedLambda` 从它解析属性名；`Path` 是 api 侧的值对象，后端只能通过 [refs()]
/// 读取它内部保存的原始 lambda 链。两者刻意不互相继承，避免后端把值对象当 lambda 解析。
///
/// @param <T> 根实体类型
/// @param <U> 属性值类型
public interface Path<T, U>
        extends Expression<T, U>,
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

    /// 属性路径链：**用户传进来的原始方法引用**，根→叶逐段（单段路径返回长度 1 的列表）。
    ///
    /// 这是后端解析属性名的唯一入口：`core.builder` 逐段用 `SerializedLambda` 解析。
    /// 列表里**绝不会**出现 api 的值对象。
    ///
    /// @return 不可变路径链，元素均为用户传入的 `PathRef`
    List<PathRef<?, ?>> refs();
}
