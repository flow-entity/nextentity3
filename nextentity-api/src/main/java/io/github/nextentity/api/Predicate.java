package io.github.nextentity.api;

/// 谓词：可继续与其它条件组合的布尔表达式。
///
/// 后端解释 `where(Expression<Boolean>)` 的入参时按两级判定：**实现 `Op` 的谓词**携带
/// `operation()/refs()/arguments()`；**实现 [SkippedCondition] 的谓词**是
/// `*IfNotNull`/`*IfNotEmpty` 短路产生的占位（恒真，后端视为"无附加条件"）；两者都不是的
/// 谓词既没有可下发的节点、也不是短路占位，后端一律**当场拒绝**
/// （`IllegalArgumentException`），不会静默忽略。
///
/// 组合算子（`and`/`or`/`not`、含集合形态）在 [LogicalOps] 里，本接口只多一个
/// `or(PathRef)` 便捷重载。
///
/// @param <T> 根实体类型
public interface Predicate<T> extends Expression<T, Boolean>, LogicalOps<T, Predicate<T>> {

    /// 与一条属性路径上的条件做逻辑或。
    ///
    /// 等价于 `or(Path.of(ref).<算子>(...))`，但省掉一次 `Path.of`。
    ///
    /// @param ref 属性路径，非 `null`
    /// @param <N> 属性值类型
    /// @return 属性算子：先选算子，再追加到整条 `or` 条件上
    <N> PathOperator<T, N, Predicate<T>> or(PathRef<T, N> ref);
}
