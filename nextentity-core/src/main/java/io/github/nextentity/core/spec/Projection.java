package io.github.nextentity.core.spec;

/// 投影项：`SELECT` 列表里的一项。
///
/// 只有两种形态：裸路径（`Order::getId`）与路径上的聚合（`Path.of(...).max()`）。
/// "投影成 DTO/record" 在 3.x 由执行器的装配器完成，不是 AST 节点。
public sealed interface Projection permits PathProjection, AggregateProjection {}
