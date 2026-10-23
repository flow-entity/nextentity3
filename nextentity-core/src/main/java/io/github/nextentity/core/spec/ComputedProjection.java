package io.github.nextentity.core.spec;

import java.util.Objects;

/// 计算投影项：`select(Path.of(X::getSalary).mul(12))` 这一列的 AST。
///
/// 与 [PathProjection] / [AggregateProjection] 并列，而不是新开节点类型：
/// 投影只有"表达式"这一件事，区别只在表达式的形态。
///
/// @param operand 投影表达式
public record ComputedProjection(Operand operand) implements Projection {

    /// 紧凑构造器：表达式必填。
    public ComputedProjection {
        Objects.requireNonNull(operand, "operand");
    }
}
