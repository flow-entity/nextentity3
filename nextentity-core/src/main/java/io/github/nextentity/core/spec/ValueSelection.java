package io.github.nextentity.core.spec;

import java.util.List;
import java.util.Objects;

/// 表达式查询：`SELECT 投影项...`，单项即单值结果，多项即元组结果。
///
/// @param items    投影项，非空
/// @param distinct 是否 `SELECT DISTINCT`
public record ValueSelection(List<Projection> items, boolean distinct) implements Selection {

    /// 紧凑构造器：校验并复制为不可变列表。
    public ValueSelection {
        Objects.requireNonNull(items, "items");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("表达式查询至少需要一个投影项");
        }
        items = List.copyOf(items);
    }
}
