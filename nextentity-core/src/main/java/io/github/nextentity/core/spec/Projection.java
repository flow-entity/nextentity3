package io.github.nextentity.core.spec;

/// 投影项：`SELECT` 列表里的一项。
///
/// 三种形态：裸路径（`Order::getId`）、路径上的聚合（`Path.of(...).max()`）、
/// 计算表达式（`Path.of(...).mul(12)`）。"投影成 DTO/record"由调用方自己映射，不是 AST 节点。
public sealed interface Projection permits PathProjection, AggregateProjection, ComputedProjection {}
