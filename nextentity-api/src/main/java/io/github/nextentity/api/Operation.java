package io.github.nextentity.api;

/// 表达式值上可能出现的运算。
///
/// 收录原则是「有真实调用点或已冻结的设计」：`notLike` / `notContains` / `notStartsWith` /
/// `notEndsWith` 依旧没有对位（`not()` + 正向算子即可表达）。
/// 布尔常量（`TRUE`/`FALSE`）也不在此列："恒真"由「不实现 `Op` 的 `Predicate`」表达，
/// "恒假"由后端从 `IN` + 空参推导。
///
/// 这个枚举是 **值到 AST 的线格式**，不是第二套 AST：把常量翻译成 `core.spec` 节点是后端的
/// 唯一职责，且后端必须用**无 `default` 的 `switch` 表达式**遍历，新增常量会让后端编译失败。
///
/// 注意：**不实现 `Op` 的 `Predicate`** 不是任何一种运算，而是 `*IfNotNull`/`*IfNotEmpty`
/// 短路产生的占位（恒真）；后端遇到它应视为"无附加条件"，不要为它建节点。
public enum Operation {

    /// 等于。
    EQ,

    /// 不等于。
    NE,

    /// 大于等于。
    GE,

    /// 大于。
    GT,

    /// 小于等于。
    LE,

    /// 小于。
    LT,

    /// 在集合内；空集合的语义（恒假）由后端判定。
    IN,

    /// 不在集合内。
    NOT_IN,

    /// 为 `null`。
    IS_NULL,

    /// 不为 `null`。
    IS_NOT_NULL,

    /// 闭区间。
    BETWEEN,

    /// 子串匹配，通配符（`%v%`）由后端加。
    CONTAINS,

    /// LIKE 匹配，模式自带通配符：本常量不添加 `%`，只有 `CONTAINS` 系列才展开成 `%value%`。
    LIKE,

    /// 逻辑与；`arguments()` 为子条件。
    AND,

    /// 逻辑或；`arguments()` 为子条件。
    OR,

    /// 逻辑非；`arguments()` 为单个子条件。
    NOT,

    /// 计数。
    COUNT,

    /// 去重计数。
    COUNT_DISTINCT,

    /// 最大值。
    MAX,

    /// 最小值。
    MIN,

    /// 平均值；结果为 `Double`，与各方言 `AVG` 的返回类型一致。
    AVG,

    /// 求和。
    SUM,

    /// 加法；`subject()` 是左操作数，`arguments()` 里是右操作数的值或表达式。
    ADD,

    /// 减法。
    SUB,

    /// 乘法。
    MUL,

    /// 除法。
    DIV,

    /// 取模。
    MOD
}
