package io.github.nextentity.core.spec;

import java.util.Objects;

/// 聚合投影项：`函数(路径)`。
///
/// @param function 聚合函数
/// @param path     被聚合的属性路径
public record AggregateProjection(AggregateFunction function, AttributePath path) implements Projection {

    /// 紧凑构造器：两字段必填。
    public AggregateProjection {
        Objects.requireNonNull(function, "function");
        Objects.requireNonNull(path, "path");
    }
}
