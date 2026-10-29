package io.github.nextentity.jdbc.dialect;

/// 锁片段：`tableSuffix` 接根表别名后（自定义方言用，可为空串）；`statementSuffix` 排窗口之后。
///
/// @param tableSuffix     FROM 里根表别名之后的表级提示（无则空串）
/// @param statementSuffix 语句尾的锁子句（默认/PG ` for update` 一族）
public record LockClause(String tableSuffix, String statementSuffix) {}
