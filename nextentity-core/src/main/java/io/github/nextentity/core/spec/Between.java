package io.github.nextentity.core.spec;

import java.util.Objects;

/// 闭区间节点：`路径 BETWEEN 下界 AND 上界`（含两端）。
///
/// 不拆成 `GE AND LE` 两节点：拆开会让"一个算子"在 AST 里变成"两个节点 + 一次逻辑组合"，
/// 编译器与快照断言都要多认一种等价形态。
///
/// @param path  属性路径
/// @param lower 下界值
/// @param upper 上界值
public record Between(AttributePath path, Object lower, Object upper) implements Spec {

    /// 紧凑构造器：path、lower、upper 三个字段都必须非 `null`。
    public Between {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(lower, "lower");
        Objects.requireNonNull(upper, "upper");
    }
}
