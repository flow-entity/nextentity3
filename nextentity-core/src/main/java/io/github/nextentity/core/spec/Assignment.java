package io.github.nextentity.core.spec;

import java.util.Objects;

/// 一条列赋值：`SET path = value`。
///
/// `value` 为 `null` 表示把该列置为 SQL `NULL`（`SET path = NULL`）——不是"跳过这一列"，
/// 跳过靠不调 `set(...)` 表达。这也是[Literal]在这里不用的原因：它拒绝 null 值。
///
/// 右值只支持字面量，不支持「列 = 列」或聚合；需要那类赋值时请写原生 SQL。
///
/// @param path  被赋值的列路径，非 null
/// @param value 新值，可为 null（置 NULL）
public record Assignment(AttributePath path, Object value) {

    /// 紧凑构造器：列路径必填。
    public Assignment {
        Objects.requireNonNull(path, "path");
    }
}
