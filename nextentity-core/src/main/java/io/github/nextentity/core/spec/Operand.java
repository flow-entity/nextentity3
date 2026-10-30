package io.github.nextentity.core.spec;

/// 值表达式：条件与投影里可以出现的一侧操作数。
///
/// 六种形态覆盖了本次新增能力的全部需要：
/// - [Literal]：字面量（`1`、`"A"`、`Instant` …）
/// - [AttributePath]：实体属性（单段或多段关联路径）
/// - [AggregateOperand]：聚合结果（`max(x)`、`count(*)`）
/// - [ArithmeticOperand]：算术结果（`salary * 12 + 1000`）
/// - [StringFunctionOperand]：一元字符串函数结果（`LOWER(name)`、`LENGTH(name)`）
/// - [SubstringOperand]：字符串截取结果（`SUBSTRING(name, offset, length)`）
///
/// 它是纯值对象：不含后端类型，可断言、可复用；后端用**无 `default` 的 `switch`** 穷尽渲染，
/// 新增形态会让每个后端编译失败。
public sealed interface Operand
        permits Literal, AttributePath, AggregateOperand, ArithmeticOperand, StringFunctionOperand, SubstringOperand {}
