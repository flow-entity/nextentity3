package io.github.nextentity.api;

/// 字符串属性路径：既能在 `select(...)` 里投影，也能直接用于 `where(...)`。
///
/// 它比通用 [Path] 多一组字符串算子（[StringOps]），因此
/// `where(customer.get(Customer::getName)).contains("A")` 这类关联路径也能用字符串过滤。
///
/// @param <T> 根实体类型
public interface StringPath<T> extends Path<T, String>, StringOps<Predicate<T>> {}
