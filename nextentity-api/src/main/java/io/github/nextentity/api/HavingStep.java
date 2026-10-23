package io.github.nextentity.api;

/// HAVING 入口：分组之后的聚合条件。
///
/// 语义边界（不在代码里兜）：
/// - **不校验"必须伴随 `groupBy`"**：SQL 允许不带 `GROUP BY` 的 `HAVING`（把全表当一个分组），
///   是否合理由调用方判断；
/// - `having` 的条件只能引用聚合结果或分组列（`count() > 1`、`max(x) < 100`），
///   引用普通列的语义由数据库决定。
///
/// @param <T> 根实体类型
/// @param <U> 结果类型
public interface HavingStep<T, U> {

    /// 追加一个分组后过滤条件。
    ///
    /// 多次调用按 `AND` 累加。
    ///
    /// @param expression 条件，非 `null`
    /// @return 后续步骤（可继续排序、分页、终止）
    BaseWhereStep<T, U> having(Expression<T, Boolean> expression);
}
