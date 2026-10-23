package io.github.nextentity.api;

import java.util.Collection;

/// 布尔组合算子：`and` / `or` / `not`。
///
/// 与 [Predicate] 拆成两个接口，是为了守住「一个接口声明的公开方法 ≤ 6」的架构门禁：
/// `Predicate` 自己还要留 `or(PathRef)` 这个便捷重载。
///
/// @param <T> 根实体类型
/// @param <B> 继续链式的步骤类型
public interface LogicalOps<T, B> {

    /// 与另一个布尔表达式做逻辑与。
    ///
    /// @param other 另一个布尔表达式，非 `null`
    /// @return 组合后的条件；保留分组，不会把嵌套条件拍平
    B and(Expression<T, Boolean> other);

    /// 与另一个布尔表达式做逻辑或。
    ///
    /// @param other 另一个布尔表达式，非 `null`
    /// @return 组合后的条件；保留分组，不会把嵌套条件拍平
    B or(Expression<T, Boolean> other);

    /// 与多个布尔表达式做逻辑与。
    ///
    /// 空集合表示"没有条件"：`and(空)` 恒真，不产生任何过滤（后端应折成恒真节点）。
    ///
    /// @param others 其它布尔表达式，非 `null` 且元素非 `null`
    /// @return 组合后的条件
    B and(Collection<? extends Expression<T, Boolean>> others);

    /// 与多个布尔表达式做逻辑或。
    ///
    /// 空集合表示"没有可满足的条件"：`or(空)` 恒假，匹配不到任何行（后端应折成恒假节点）。
    ///
    /// @param others 其它布尔表达式，非 `null` 且元素非 `null`
    /// @return 组合后的条件
    B or(Collection<? extends Expression<T, Boolean>> others);

    /// 对当前条件取反。
    ///
    /// 边界：对 `*IfNotNull` / `*IfNotEmpty` 短路产生的**恒真占位**取反，结果是恒假
    /// （`NOT TRUE = FALSE`，即 `WHERE 1=0`、匹配 0 行），不是"忽略这条条件"。
    /// 例：`Path.of(X::getStatus).eqIfNotNull(null).not()` 会过滤掉所有行。
    ///
    /// @return 取反后的条件
    B not();
}
