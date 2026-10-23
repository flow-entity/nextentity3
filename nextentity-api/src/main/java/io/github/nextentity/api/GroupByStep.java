package io.github.nextentity.api;

/// 分组入口。
///
/// @param <T> 根实体类型
/// @param <U> 结果类型
public interface GroupByStep<T, U> {

    /// 按属性分组。
    ///
    /// @param ref 分组列，非 `null`；多次调用追加多列分组，不是替换
    /// @return 分组之后的步骤（可继续排序、分页、终止）
    BaseWhereStep<T, U> groupBy(PathRef<T, ?> ref);

    /// 一次追加多列分组（列序即 SQL 的 `GROUP BY` 顺序）。
    ///
    /// @param refs 分组列，非 `null` 且元素非 `null`；空数组表示不追加
    /// @return 分组之后的步骤
    @SuppressWarnings("unchecked")
    BaseWhereStep<T, U> groupBy(PathRef<T, ?>... refs);
}
