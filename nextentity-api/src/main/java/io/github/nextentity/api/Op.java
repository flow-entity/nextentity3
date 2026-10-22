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
/// - `IS_NULL`/`IS_NOT_NULL`/`COUNT`/`COUNT_DISTINCT`/`MAX`/`SUM`/`TRUE`：空
/// - `AND`/`OR`：子条件（`Expression<T, Boolean>`）
///
/// 用 `sealed` 封闭实现集合：`Op` 是「值 → AST」的线格式，语义由后端按 `operation()` 穷尽解释，
/// 不允许外部再实现出后端无法解释的运算。
public sealed interface Op permits Conditions.Leaf, Conditions.AbstractAggregate {

    /// 运算种类。
    ///
    /// @return 运算种类
    Operation operation();

    /// 属性路径链，从根属性到叶子属性。
    ///
    /// @return 不可变路径链
    List<PathRef<?, ?>> refs();

    /// 运算参数，含义见类型注释。
    ///
    /// @return 不可变参数列表
    List<Object> arguments();
}
