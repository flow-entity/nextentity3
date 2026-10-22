package io.github.nextentity.core.spec;

/// 聚合函数：只保留有真实调用点的四种。
///
/// 调用点：`MAX`（`AccountEntryRepository:83`、`AccountTypeRepository:21`）、
/// `SUM`（`PointsExpiryRepository:45`）、`COUNT`（`QimatongVoucherCodeRepository:222`）、
/// `COUNT_DISTINCT`（`ActivityClaimRecordRepository:164/177/192/223`）。
///
/// 不加 `MIN`/`AVG`：point-mall 全库 `.min(` 只有 `Math.min`/`BigDecimal.min`，`avg` 0 命中，
/// 即没有 `Path.of(...).min()` 这类调用点（GOAL §3.4 是能力上限，不是实现清单）。
/// 新增常量会让编译器与后端的 `switch` 因穷尽性检查而必须补齐分支——那正是它的作用。
public enum AggregateFunction {

    /// 最大值。
    MAX,

    /// 求和。
    SUM,

    /// 计数（`COUNT(路径)`）。
    COUNT,

    /// 去重计数（`COUNT(DISTINCT 路径)`）。
    COUNT_DISTINCT
}
