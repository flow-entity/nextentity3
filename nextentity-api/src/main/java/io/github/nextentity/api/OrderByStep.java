package io.github.nextentity.api;

/// 排序入口。
///
/// @param <T> 根实体类型
/// @param <U> 结果类型
public interface OrderByStep<T, U> {

    /// 按属性排序。
    ///
    /// @param ref 参与排序的属性
    /// @return 排序方向
    OrderOperator<T, U> orderBy(PathRef<T, ? extends Comparable<?>> ref);
}
