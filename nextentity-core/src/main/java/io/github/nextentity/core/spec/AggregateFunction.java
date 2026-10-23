package io.github.nextentity.core.spec;

/// 聚合函数。
///
/// 六种都是本次能力面的一部分：`count/countDistinct/max/min/avg` 由 `api.Aggregates` 暴露，
/// `sum` 由 `api.NumberPath` 暴露。后端必须用**无 `default` 的 `switch`** 穷尽渲染，
/// 新增函数会让每个后端编译失败——那正是它的作用。
public enum AggregateFunction {

    /// 最大值。
    MAX,

    /// 最小值。
    MIN,

    /// 平均值；结果为 `Double`，与各方言 `AVG` 的返回类型一致。
    AVG,

    /// 求和。
    SUM,

    /// 计数（`COUNT(路径)`）。
    COUNT,

    /// 去重计数（`COUNT(DISTINCT 路径)`）。
    COUNT_DISTINCT
}
