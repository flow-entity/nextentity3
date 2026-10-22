package io.github.nextentity.core.spec;

import java.util.Objects;

/// 裸路径投影项。
///
/// @param path 属性路径
public record PathProjection(AttributePath path) implements Projection {

    /// 紧凑构造器：path 必须非 `null`。
    public PathProjection {
        Objects.requireNonNull(path, "path");
    }
}
