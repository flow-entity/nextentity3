package io.github.nextentity.core.spec;

import java.util.Objects;

/// 比较节点：`路径 算子 值`。
///
/// @param path     属性路径
/// @param operator 比较算子
/// @param value    比较值，非 null
public record Comparison(AttributePath path, ComparisonOperator operator, Object value) implements Spec {

    /// 紧凑构造器：三个字段都必填，缺值属于构造期错误而不是渲染期意外。
    public Comparison {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(value, "value");
    }
}
