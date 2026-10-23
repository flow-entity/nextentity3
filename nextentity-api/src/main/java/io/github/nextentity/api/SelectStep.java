package io.github.nextentity.api;

import io.github.nextentity.api.model.Tuple2;
import io.github.nextentity.api.model.Tuple3;

/// 投影入口：决定查询返回什么。
///
/// 投影形态：单列、双列 [Tuple2]、三列 [Tuple3]、聚合/算术表达式，
/// 以及列集由 [ProjectionMapper] 声明、不在本接口签名里写死的投影（结果类型由映射器决定）。
/// 四列及以上由 [ProjectionMappers] 产出的映射器覆盖。以上形态都能被 [DistinctStep#distinct()] 修饰成 `SELECT DISTINCT`。
/// 投影成 DTO 靠调用方自己的映射代码（[#project(ProjectionMapper)] 的映射器，或
/// [io.github.nextentity.api.model.Slice#to] 之类的手写转换）；本接口不提供按类反射/注解自动映射的重载。
///
/// @param <T> 根实体类型
public interface SelectStep<T> extends DistinctStep<T> {

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

    /// 投影三列，结果为 [Tuple3]（具名 `get0`~`get2` 且带类型参数，列序写反是编译错误）。
    ///
    /// @param first  第一列，非 `null`
    /// @param second 第二列，非 `null`
    /// @param third  第三列，非 `null`
    /// @param <A> 第一列类型
    /// @param <B> 第二列类型
    /// @param <C> 第三列类型
    /// @return 后续步骤；一次查询只有一份投影，再次 `select` 会替换前一份
    ///
    /// 四列及以上不在这里加重载（本接口方法数有上限），改用 [ProjectionMappers] 产出的映射器。
    <A, B, C> BaseWhereStep<T, Tuple3<A, B, C>> select(
            Expression<T, A> first, Expression<T, B> second, Expression<T, C> third);

    /// 用显式映射器投影：查哪些列、每行怎么造 `R` 都由映射器决定。
    ///
    /// 列数没有下限（单列也允许），列序即 [ProjectionMapper#expressions()] 的顺序。映射器只在这一步
    /// 求值一次，随后再次投影（`select(...)` 或 `project(...)`）会把它整体替换掉。
    ///
    /// @param mapper 投影映射器，非 `null`
    /// @param <R> 结果类型
    /// @return 后续步骤，结果类型为 `R`
    /// @throws IllegalArgumentException `expressions()` 为空、含 `null`（消息带下标），
    ///     或含后端无法解释的表达式（api 侧可解释的是 `Path`、`PathRef` 与 `Op`）；这些都在本方法内失败，
    ///     不会拖到编译/执行阶段
    <R> BaseWhereStep<T, R> project(ProjectionMapper<T, R> mapper);
}
