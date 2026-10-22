package io.github.nextentity.api;

import io.github.nextentity.api.model.Tuple2;

/// 投影入口：决定查询返回什么。
///
/// @param <T> 根实体类型
public interface SelectStep<T> {

    /// 投影单列（方法引用形式）。
    ///
    /// @param ref 属性路径
    /// @param <R> 投影类型
    /// @return 后续步骤
    <R> BaseWhereStep<T, R> select(PathRef<T, R> ref);

    /// 投影单列（表达式形式，含 `Path.of(...).max()` 这类聚合）。
    ///
    /// @param expression 表达式
    /// @param <R> 投影类型
    /// @return 后续步骤
    <R> BaseWhereStep<T, R> select(Expression<T, R> expression);

    /// 投影两列，结果为 `Tuple2`。
    ///
    /// @param first 第一列
    /// @param second 第二列
    /// @param <A> 第一列类型
    /// @param <B> 第二列类型
    /// @return 后续步骤
    <A, B> BaseWhereStep<T, Tuple2<A, B>> select(Expression<T, A> first, Expression<T, B> second);

    /// 去重投影单列。
    ///
    /// @param ref 属性路径
    /// @param <R> 投影类型
    /// @return 后续步骤
    <R> BaseWhereStep<T, R> selectDistinct(PathRef<T, R> ref);
}
