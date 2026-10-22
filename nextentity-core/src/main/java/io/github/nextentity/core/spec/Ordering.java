package io.github.nextentity.core.spec;

import java.util.Objects;

/// 排序项：`ORDER BY 路径 方向`。
///
/// @param path      属性路径
/// @param direction 排序方向
public record Ordering(AttributePath path, Direction direction) {

    /// 紧凑构造器：path 与 direction 都必须非 `null`。
    public Ordering {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(direction, "direction");
    }

    /// 排序方向。
    public enum Direction {

        /// 升序。
        ASC,

        /// 降序。
        DESC
    }
}
