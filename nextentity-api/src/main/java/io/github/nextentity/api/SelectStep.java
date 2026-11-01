package io.github.nextentity.api;

import io.github.nextentity.api.model.Tuple2;
import io.github.nextentity.api.model.Tuple3;

/// 投影入口：决定查询返回什么。
///
/// 投影形态：单列、双列 [Tuple2]、三列 [Tuple3]、聚合/算术表达式、按 DTO 类型反射出的扁平投影
/// （[#select(Class)]），以及列集由 [ProjectionMapper] 声明、不在本接口签名里写死的投影（结果类型由映射器决定）。
/// 四列及以上由 [ProjectionMappers] 产出的映射器覆盖。以上形态都能被 [DistinctStep#distinct()] 修饰成
/// `SELECT DISTINCT`（DTO 投影写作 `distinct().select(Dto.class)`）。
/// 需要改名、嵌套或计算列时仍用 [#project(ProjectionMapper)] 手写映射器，[#select(Class)] 只覆盖
/// "DTO 属性名与实体属性同名"的扁平场景（JPA 后端另含 `@Embedded` 组件里的同名叶子，见该方法的 Javadoc）。
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

    /// 按 DTO 类型反射投影：DTO 的每个属性一列，属性名按名匹配实体属性。
    ///
    /// 目标类型的两种可用形态：
    ///
    /// - `record`：属性名取规范构造器的参数名，列序即参数序；
    /// - 普通类：属性名由公开的 `setXxx` 定位（`setName` → `name`），需要无参构造器，列序按属性名排序。
    ///
    /// 接口与抽象类不支持：接口投影要靠运行期代理，本轮不提供——改用 [#project(ProjectionMapper)] 手写映射器。
    ///
    /// 属性名按名匹配实体属性，先找直接属性；命不中时 **JPA 后端**再到实体的 `@Embedded` 组件（可多级）
    /// 里找同名叶子，所以嵌平投影（DTO 的 `street` ↔ 实体的 `address.street`）也属本形态。没有注解面
    /// 给这个映射指路，按名回找是唯一能表达的形态。
    ///
    /// 关联属性（`department.name` 那种跨表路径）仍不属本形态，用 [#project(ProjectionMapper)]；
    /// 属性名无法与实体属性做编译期校验，写错会在后端渲染时失败。
    ///
    /// 这是显式 opt-in 的按名自动映射，不是"凡是 DTO 就自动反射"：不调用本方法时行为完全不变。
    ///
    /// 目标类型恰好是实体类型时退化为整实体查询（结果为实体实例），此时 `distinct()` 不可用：
    /// 整实体查询的列集不是显式列表，`SELECT DISTINCT` 表达不出来。
    ///
    /// 失败时机分两段：目标类型的**形状**不可映射（record 无组件、类无 `setXxx`、普通类缺无参构造器、
    /// 接口/抽象类）在本方法内抛 `IllegalArgumentException`；**属性名**解析失败（实体上既没有该属性、
    /// 嵌入组件里也没有同名叶子，或同名叶子出现在多个 `@Embedded` 组件里）要到后端渲染投影列时才抛。
    /// 计数语句本身不含投影列，但**查询在计数前仍被完整编译**（投影列随之解析），所以写错的属性名在
    /// `count()` / `exists()` 上同样会失败，不会拖到 `list()` 才暴露。`groupBy(...)` 之后的 `count()` 是
    /// 例外：分组计数把投影换成组键（内层 `select` 只渲染分组键，DTO 投影列不参与编译），写错的属性名
    /// 要到 `list()` 才暴露。JDBC 后端没有嵌入回找，属性名只按实体直接属性匹配。
    ///
    /// DTO 属性落在**关联属性**上（如 `record Dto(Long id, Customer customer)`）不是本形态：两个后端都
    /// 显式拒绝（`投影关联对象未支持`），跨表列请写进 [#project(ProjectionMapper)]。
    ///
    /// @param type DTO 类型，非 `null`
    /// @param <R> 结果类型
    /// @return 后续步骤，结果类型为 `R`
    /// @throws IllegalArgumentException 目标类型没有可映射的属性（record 无组件、类无 `setXxx`），
    ///     普通类缺无参构造器，或目标类型是接口/抽象类；这些都在本方法内失败
    /// @throws UnsupportedOperationException 目标类型是实体类型本身却又调了 [DistinctStep#distinct()]
    <R> BaseWhereStep<T, R> select(Class<R> type);
}
