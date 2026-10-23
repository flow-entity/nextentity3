package io.github.nextentity.api;

import io.github.nextentity.api.model.Tuple;
import io.github.nextentity.api.model.Tuple2;

/// 投影入口：决定查询返回什么。
///
/// 投影形态：单列、双列 [Tuple2]、**3 列及以上 [Tuple]**、聚合/算术表达式。
/// 投影成 DTO 由调用方自己映射（例如通过 [io.github.nextentity.api.model.Slice#to] 或手写转换），
/// 本接口不提供「投影成某个类」的重载。
///
/// @param <T> 根实体类型
public interface SelectStep<T> {

    /// 投影单列（方法引用形式）。
    ///
    /// @param ref 属性路径，非 `null`
    /// @param <R> 投影类型
    /// @return 后续步骤；一次查询只有一份投影，再次 `select` 会替换前一份
    <R> BaseWhereStep<T, R> select(PathRef<T, R> ref);

    /// 投影单列（表达式形式，含 `Path.of(...).max()` 这类聚合与算术）。
    ///
    /// @param expression 表达式（`Path`、聚合结果或算术结果），非 `null`
    /// @param <R> 投影类型
    /// @return 后续步骤；一次查询只有一份投影，再次 `select` 会替换前一份
    <R> BaseWhereStep<T, R> select(Expression<T, R> expression);

    /// 投影两列，结果为 [Tuple2]。
    ///
    /// @param first  第一列，非 `null`
    /// @param second 第二列，非 `null`；列序即 `Tuple2` 的 `get0`/`get1`
    /// @param <A> 第一列类型
    /// @param <B> 第二列类型
    /// @return 后续步骤；一次查询只有一份投影，再次 `select` 会替换前一份
    <A, B> BaseWhereStep<T, Tuple2<A, B>> select(Expression<T, A> first, Expression<T, B> second);

    /// 投影三列及以上，结果为 [Tuple]（按位置取列，列序即实参顺序）。
    ///
    /// @param first  第一列，非 `null`
    /// @param second 第二列，非 `null`
    /// @param third  第三列，非 `null`
    /// @param rest   第四列起的其余列，可省略；元素非 `null`
    /// @param <A> 第一列类型
    /// @param <B> 第二列类型
    /// @param <C> 第三列类型
    /// @return 后续步骤；一次查询只有一份投影，再次 `select` 会替换前一份
    <A, B, C> BaseWhereStep<T, Tuple> select(
            Expression<T, A> first, Expression<T, B> second, Expression<T, C> third, Expression<T, ?>... rest);

    /// 去重投影单列。
    ///
    /// 去重发生在数据库侧（`SELECT DISTINCT`），`count()` 相应返回去重后的投影值个数。
    ///
    /// @param ref 属性路径，非 `null`；api 没有多列 distinct 重载
    /// @param <R> 投影类型
    /// @return 后续步骤；一次查询只有一份投影，再次 `select`/`selectDistinct` 会替换前一份
    <R> BaseWhereStep<T, R> selectDistinct(PathRef<T, R> ref);
}
