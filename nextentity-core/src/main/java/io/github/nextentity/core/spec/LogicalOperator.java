package io.github.nextentity.core.spec;

/// 逻辑组合算子。
public enum LogicalOperator {

    /// 全部成立。
    AND,

    /// 任一成立。
    OR,

    /// 取反，只接受一个操作数。
    NOT
}
