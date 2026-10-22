package io.github.nextentity.core.spec;

import java.util.Objects;

/// 空值判断节点：`路径 IS NULL` / `路径 IS NOT NULL`。
///
/// 单独成节点而不是塞进 {@link Comparison}：这类算子不接受比较值，
/// 混在一起会让"有值/无值"变成运行期约定。
///
/// @param path    属性路径
/// @param negated `true` 表示 `IS NOT NULL`
public record NullCheck(AttributePath path, boolean negated) implements Spec {

    /// 紧凑构造器：path 必须非 `null`（`negated` 允许 `false`）。
    public NullCheck {
        Objects.requireNonNull(path, "path");
    }
}
