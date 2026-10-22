package io.github.nextentity.api;

/// 条件入口：所有步骤都可以继续追加条件。
///
/// `where(...)` 的 5 个重载对应 5 种入参形态。后端**只能**从 `PathRef`（用户 lambda）或
/// [Path#refs()] 解析属性；`Path` 不是 `PathRef`，不得当 lambda 解析。
///
/// @param <T> 根实体类型
/// @param <SELF> 继续链式时返回的步骤类型
public interface Whereable<T, SELF extends Whereable<T, SELF>> {

    /// 追加一个已经组合好的布尔表达式（例如 `Predicate` 变量）。
    ///
    /// @param expression 条件
    /// @return 下一步
    SELF where(Expression<T, Boolean> expression);

    /// 追加一条方法引用形式的属性条件。
    ///
    /// @param ref 方法引用形式的属性
    /// @param <N> 属性值类型
    /// @return 属性算子
    <N> PathOperator<T, N, ? extends SELF> where(PathRef<T, N> ref);

    /// 追加一条字符串方法引用形式的属性条件。
    ///
    /// @param ref 方法引用形式的字符串属性
    /// @return 字符串算子
    StringOperator<T, ? extends SELF> where(PathRef.StringRef<T> ref);

    /// 追加一条属性路径（`Path.of(...)` / `EntityPath.of(...)` 的结果）上的条件。
    ///
    /// @param path 属性路径
    /// @param <N> 属性值类型
    /// @return 属性算子
    <N> PathOperator<T, N, ? extends SELF> where(Path<T, N> path);

    /// 追加一条字符串属性路径上的条件。
    ///
    /// @param path 字符串属性路径
    /// @return 字符串算子
    StringOperator<T, ? extends SELF> where(StringPath<T> path);
}
