package io.github.nextentity.core.spec;

/// 查询形态：`SELECT` 什么。
///
/// 只有两种：查实体本身，或者查一组表达式（单列 / 双列元组 / 聚合）。
public sealed interface Selection permits EntitySelection, ValueSelection {}
