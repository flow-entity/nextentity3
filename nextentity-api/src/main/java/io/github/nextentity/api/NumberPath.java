package io.github.nextentity.api;

/// 数值属性路径：比通用路径多一组算术算子，并支持 `sum()` / `avg()` 聚合。
///
/// `avg()` 只挂在数值路径上（不在 [Aggregates]）：对字符串列求平均是渲染期才炸的错误，
/// 用类型系统挡掉。
///
/// @param <T> 根实体类型
/// @param <U> 数值类型
public interface NumberPath<T, U extends Number> extends Path<T, U>, NumberArithmetic<T, U> {

    /// 求和。
    ///
    /// @return `SUM(路径)` 表达式；无行时结果为 `null`（不是 `0`）
    AggregateValue<T, U> sum();

    /// 平均值。
    ///
    /// 返回类型固定为 `Double`，与各方言 `AVG` 的结果类型一致；要 `BigDecimal` 请自行转换。
    ///
    /// @return `AVG(路径)` 表达式；无行时结果为 `null`
    AggregateValue<T, Double> avg();
}
