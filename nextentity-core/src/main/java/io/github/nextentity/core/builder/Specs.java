package io.github.nextentity.core.builder;

import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Between;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.Constant;
import io.github.nextentity.core.spec.In;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.NullCheck;
import io.github.nextentity.core.spec.Operand;
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

    /// 单值比较（两侧都是操作数：字面量、属性路径、聚合、算术结果）。
    ///
    /// @param left  左操作数
    /// @param op    比较算子
    /// @param right 右操作数
    /// @return 比较节点
    static Spec comparison(Operand left, ComparisonOperator op, Operand right) {
        return new Comparison(left, op, right);
    }

    /// 模糊匹配（调用方自带通配符）。
    ///
    /// @param subject 主语操作数
    /// @param pattern LIKE 模式
    /// @return 比较节点
    static Spec like(Operand subject, String pattern) {
        return new Comparison(subject, ComparisonOperator.LIKE, Literal.of(pattern));
    }

    /// 包含子串：`%value%`（2.2.3 `ExpressionBuilder:520-522`）。
    ///
    /// @param subject 主语操作数
    /// @param value   子串
    /// @return 比较节点
    static Spec contains(Operand subject, Object value) {
        return like(subject, "%" + value + "%");
    }

    /// 集合成员：空集合折成恒假/恒真。
    ///
    /// @param path    属性路径（`IN` 的主语只能是路径）
    /// @param values  候选值
    /// @param negated `true` 表示 `NOT IN`
    /// @return 集合节点或恒真/恒假节点
    static Spec membership(AttributePath path, Collection<?> values, boolean negated) {
        if (values.isEmpty()) {
            return Constant.of(negated);
        }
        List<Object> copy = new ArrayList<>(values);
        return new In(path, copy, negated);
    }

    /// 闭区间。
    ///
    /// @param subject 主语操作数
    /// @param lower   下界操作数
    /// @param upper   上界操作数
    /// @return 区间节点
    static Spec between(Operand subject, Operand lower, Operand upper) {
        return new Between(subject, lower, upper);
    }

    /// 空值判断。
    ///
    /// @param path    属性路径（`IS NULL` 的主语只能是路径）
    /// @param negated `true` 表示 `IS NOT NULL`
    /// @return 空值判断节点
    static Spec nullCheck(AttributePath path, boolean negated) {
        return new NullCheck(path, negated);
    }
}
