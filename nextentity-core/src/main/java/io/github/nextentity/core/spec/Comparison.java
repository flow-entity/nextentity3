package io.github.nextentity.core.spec;

import java.util.Objects;

/// 比较节点：`路径 算子 值`。
///
/// 值是否必填由算子决定：`IS_NULL` / `IS_NOT_NULL` 不接受值，其余算子必须有值。
///
/// @param path     属性路径
/// @param operator 比较算子
/// @param value    比较值，无值算子为 null
public record Comparison(AttributePath path, ComparisonOperator operator, Object value) implements Spec {

    /// 紧凑构造器：按算子校验取值合法性。
    public Comparison {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(operator, "operator");
        if (operator.valueRequired() && value == null) {
            throw new IllegalArgumentException("算子 " + operator + " 需要比较值");
        }
        if (!operator.valueRequired() && value != null) {
            throw new IllegalArgumentException("算子 " + operator + " 不接受比较值");
        }
    }

    /// 构造等值比较。
    ///
    /// @param path  属性路径
    /// @param value 比较值
    /// @return 比较节点
    public static Comparison eq(AttributePath path, Object value) {
        return new Comparison(path, ComparisonOperator.EQ, value);
    }

    /// 构造「为空」比较。
    ///
    /// @param path 属性路径
    /// @return 比较节点
    public static Comparison isNull(AttributePath path) {
        return new Comparison(path, ComparisonOperator.IS_NULL, null);
    }
}
