package io.github.nextentity.core.builder;

import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Between;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.Constant;
import io.github.nextentity.core.spec.In;
import io.github.nextentity.core.spec.NullCheck;
import io.github.nextentity.core.spec.Spec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/// 语义映射的唯一出口：把「路径 + 运算 + 参数」变成 `core.spec` 节点。
///
/// 两个入口共用它，语义因此只有一份：
/// - `api.Op` 值（`Path.of(...)`、`where(...)` 之后由 api 组装的条件）由 {@link OpInterpreter} 翻译；
/// - 查询链上的算子（`query.where(X::getY).eq(v)`）由 {@link PathStepOperator} 直接调用。
///
/// 空集合语义在这里定死（2.2.3 `ExpressionNode:78-88`）：`IN` 空 → `1=0`、`NOT IN` 空 → `1=1`。
final class Specs {

    private Specs() {}

    /// 单值比较。
    ///
    /// @param path  属性路径，非 `null`
    /// @param op    单值比较算子
    /// @param value 比较值，非 `null`（缺值属于构造期错误）
    /// @return `Comparison` 节点
    static Spec comparison(AttributePath path, ComparisonOperator op, Object value) {
        return new Comparison(path, op, value);
    }

    /// 模糊匹配（调用方自带通配符）。
    ///
    /// @param path    属性路径，非 `null`
    /// @param pattern LIKE 模式，原样进 `LIKE`，本方法不拼 `%`
    /// @return `Comparison` 节点（算子 `LIKE`）
    static Spec like(AttributePath path, String pattern) {
        return new Comparison(path, ComparisonOperator.LIKE, pattern);
    }

    /// 包含子串：`%value%`（2.2.3 `ExpressionBuilder:520-522`）。
    ///
    /// @param path  属性路径，非 `null`
    /// @param value 子串，非 `null`；两侧各补一个 `%`
    /// @return `Comparison` 节点（算子 `LIKE`）
    static Spec contains(AttributePath path, Object value) {
        return like(path, "%" + value + "%");
    }

    /// 集合成员：空集合折成恒假/恒真。
    ///
    /// @param path    属性路径，非 `null`
    /// @param values  候选值；空集合折成 `Constant`
    /// @param negated `true` 表示 `NOT IN`
    /// @return `In` 节点，或空集合时的 `Constant` 单例
    static Spec membership(AttributePath path, Collection<?> values, boolean negated) {
        if (values.isEmpty()) {
            return Constant.of(negated);
        }
        List<Object> copy = new ArrayList<>(values);
        return new In(path, copy, negated);
    }

    /// 闭区间。
    ///
    /// @param path  属性路径，非 `null`
    /// @param lower 下界，含端点，非 `null`
    /// @param upper 上界，含端点，非 `null`
    /// @return `Between` 节点
    static Spec between(AttributePath path, Object lower, Object upper) {
        return new Between(path, lower, upper);
    }

    /// 空值判断。
    ///
    /// @param path    属性路径，非 `null`
    /// @param negated `true` 表示 `IS NOT NULL`
    /// @return `NullCheck` 节点
    static Spec nullCheck(AttributePath path, boolean negated) {
        return new NullCheck(path, negated);
    }
}
