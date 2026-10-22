package io.github.nextentity.jpa.compile;

import io.github.nextentity.core.spec.QuerySpec;
import jakarta.persistence.criteria.CriteriaQuery;

/// 编译产物：已渲染好的 Criteria 查询 + 原始规格。
///
/// 带原始规格的原因：Criteria 的 `Root` / `Path` / `Order` 都绑定在具体 `CriteriaQuery` 上，
/// 同一份规格的「取列表 / 计数 / 判存在」三种形态无法共用一份渲染结果，因此计数与存在性
/// 由执行器按规格重建（DESIGN §3 把「分页计数」划给 exec 层）。
///
/// @param query 列表查询（`SELECT 实体` 或 `SELECT cb.array(...)`）
/// @param spec  原始查询规格
public record JpaQuery(CriteriaQuery<?> query, QuerySpec spec) {}
