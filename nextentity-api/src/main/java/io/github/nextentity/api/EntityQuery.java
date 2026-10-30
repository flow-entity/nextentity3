package io.github.nextentity.api;

/// 实体查询入口：既能投影（`select(...)`），也能声明预加载（`fetch(...)`），还能直接查实体。
///
/// @param <T> 实体类型
public interface EntityQuery<T> extends SelectStep<T>, BaseWhereStep<T, T>, FetchStep<T> {}
