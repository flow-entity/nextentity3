package io.github.nextentity.api;

import java.util.List;

/// 引擎读取用的表达式值：把「属性路径链 + 运算 + 参数」原样交给后端解释。
///
/// `api` 只负责承载用户写下的表达式，**不做任何语义决策**；把 `Op` 翻译成 `Spec` 节点是后端
/// （`core.builder`）的职责，因此语义仍然只有一份。
///
/// `arguments()` 的内容随 `operation()` 变化：
///
/// - 比较/区间/包含类：一个比较值（`IN`/`NOT_IN` 为一个集合，`BETWEEN` 为两个值）
/// - `IS_NULL`/`IS_NOT_NULL`/`COUNT`/`COUNT_DISTINCT`/`MAX`/`MIN`/`AVG`/`SUM`：空
/// - `AND`/`OR`：子条件（`Expression<T, Boolean>`）；`NOT`：单个子条件
/// - `ADD`/`SUB`/`MUL`/`DIV`/`MOD`：右操作数（一个值或另一个表达式）
///
/// 用 `sealed` 封闭实现集合：`Op` 是「值 → AST」的线格式，语义由后端按 `operation()` 穷尽解释，
/// 不允许外部再实现出后端无法解释的运算。
public sealed interface Op permits Conditions.Leaf, Conditions.AbstractAggregate, Conditions.AbstractArithmetic {

    /// 运算种类。
    ///
    /// @return 运算种类；后端按它穷尽分派（`switch` 不写 `default`）
    Operation operation();

    /// 属性路径链，从根属性到叶子属性。
    ///
    /// @return 不可变链，根→叶；聚合运算返回的是被聚合的路径
    List<PathRef<?, ?>> refs();

    /// 运算参数，含义见类型注释。
    ///
    /// @return 不可变参数列表；形态随 `operation()` 变化
    List<Object> arguments();

    /// 左侧主体表达式；`null` 表示主语就是 [refs()] 解析出的属性路径。
    ///
    /// `max(id) > 5`、`salary mod 3 = 0`、`a > b` 的左操作数不是属性路径，
    /// 必须随线格式一起传下去，否则后端会把 `max(id)` 当成 `id`。
    ///
    /// @return 左侧表达式，或 `null`
    Op subject();
}
