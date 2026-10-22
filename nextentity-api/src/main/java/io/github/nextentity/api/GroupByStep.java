package io.github.nextentity.api;

/// 分组入口。
///
/// @param <T> 根实体类型
/// @param <U> 结果类型
public interface GroupByStep<T, U> {

    /// 按属性分组。
    ///
    /// @param ref 分组属性
    /// @return 分组之后的步骤（可继续排序、分页、终止）
    HavingStep<T, U> groupBy(PathRef<T, ?> ref);
}
