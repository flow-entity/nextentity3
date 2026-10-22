package io.github.nextentity.api;

/// `where(...)` 返回的属性算子：比较、区间、空值、集合、区间条件全集。
///
/// @param <T> 根实体类型
/// @param <U> 属性值类型
/// @param <B> 继续链式的步骤类型
public interface PathOperator<T, U, B>
        extends Comparison<T, U, B>,
                Range<T, U, B>,
                RangeIfNotNull<T, U, B>,
                Nullness<B>,
                Membership<T, U, B>,
                Between<T, U, B> {}
