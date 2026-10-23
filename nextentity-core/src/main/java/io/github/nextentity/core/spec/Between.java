package io.github.nextentity.core.spec;

import java.util.Objects;

/// 闭区间节点：`主语 BETWEEN 下界 AND 上界`（含两端）。
///
/// 不拆成 `GE AND LE` 两节点：拆开会让"一个算子"在 AST 里变成"两个节点 + 一次逻辑组合"，
/// 编译器与快照断言都要多认一种等价形态。`NOT BETWEEN` 由 builder 折成 `Logical.not(本节点)`。
///
/// @param subject 主语操作数
/// @param lower   下界操作数
/// @param upper   上界操作数
public record Between(Operand subject, Operand lower, Operand upper) implements Spec {

    /// 紧凑构造器：三字段必填。
    public Between {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(lower, "lower");
        Objects.requireNonNull(upper, "upper");
    }
}
