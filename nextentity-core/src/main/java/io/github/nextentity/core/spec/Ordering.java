package io.github.nextentity.core.spec;

import java.util.Objects;

/// 排序项：`ORDER BY 路径 方向`。
///
/// @param path      属性路径
/// @param direction 排序方向
public record Ordering(AttributePath path, Direction direction) {

    /// 紧凑构造器：两字段必填。
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
