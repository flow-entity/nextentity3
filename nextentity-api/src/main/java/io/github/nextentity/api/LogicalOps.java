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
    /// 空集合表示"没有可组合的条件"：`or(空)` 返回**接收者本身**（`a ∨ 空 = a`），**不是**恒假。
    /// 这条语义是为"可选条件拼接"服务的：`租户边界.or(动态过滤列表)` 在列表为空时仍然只查
    /// 租户边界内的行——既不会退化成 0 行，也不会放宽成全表。
    ///
    /// 若需要"没有可满足条件 → 一行都不返回"，请显式构造恒假条件（例如自相矛盾的比较），
    /// 不要依赖空集合：`a ∨ 空 = a` 与 `a ∧ 空 = a` 是同一套"空即无条件"约定。
    ///
    /// **注意集合元素**：元素若本身是 `*IfNotNull` / `*IfNotEmpty` 短路出来的恒真占位，
    /// 按 `a ∨ TRUE = TRUE` 处理（整条 `or` 恒真 = 匹配全表），与"空集合"不是一回事。
    /// 动态拼条件时请先把这些占位从列表里去掉，再交给 `or(...)`。
    ///
    /// @param others 其它布尔表达式，非 `null` 且元素非 `null`
    /// @return 组合后的条件；`others` 为空时返回当前条件
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
