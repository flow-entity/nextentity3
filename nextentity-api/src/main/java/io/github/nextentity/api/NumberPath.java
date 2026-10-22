package io.github.nextentity.api;

/// 数值属性路径：比通用路径多一个求和算子。
///
/// @param <T> 根实体类型
/// @param <U> 数值类型
public interface NumberPath<T, U extends Number> extends Path<T, U>, PathRef.NumberRef<T, U>, NumberExpression<T, U> {}
