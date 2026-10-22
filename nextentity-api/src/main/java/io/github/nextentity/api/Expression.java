package io.github.nextentity.api;

/// 查询表达式：可被投影、可参与比较的类型化值。
///
/// `Path`、`Predicate` 与聚合结果都实现它，`select(...)` 因此只需要一个参数类型。
///
/// @param <T> 根实体类型
/// @param <U> 表达式的值类型
public interface Expression<T, U> {}
