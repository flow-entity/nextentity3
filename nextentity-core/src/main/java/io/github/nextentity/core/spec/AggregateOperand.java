package io.github.nextentity.core.spec;

import java.util.Objects;

/// 聚合结果作为操作数：`max(x) > 5`、`having count(*) > 1` 的左侧。
///
/// 与 [AggregateProjection] 的区别是位置：投影里出现是"这一列是聚合结果"，
/// 条件里出现是"拿聚合结果去比较"。
///
/// @param function 聚合函数
/// @param path     被聚合的属性路径
public record AggregateOperand(AggregateFunction function, AttributePath path) implements Operand {

    /// 紧凑构造器：两字段必填。
    public AggregateOperand {
        Objects.requireNonNull(function, "function");
        Objects.requireNonNull(path, "path");
    }
}
