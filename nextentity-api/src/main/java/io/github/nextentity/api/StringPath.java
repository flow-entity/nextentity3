package io.github.nextentity.api;

/// 字符串属性路径：同时是 `PathRef.StringRef`，因此可以直接交给 `where(...)`。
///
/// @param <T> 根实体类型
public interface StringPath<T> extends Path<T, String>, PathRef.StringRef<T> {}
