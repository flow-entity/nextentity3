package io.github.nextentity.core.spec;

/// 实体查询：`SELECT 实体`。
///
/// 不带 `distinct` 标志：point-mall 的 2 个 `selectDistinct` 调用点都是单列投影
/// （`DigitalCardRepository:85`、`OrderImportRecordRepository:71`），"查实体 + distinct" 没有调用点。
public record EntitySelection() implements Selection {}
