package io.github.nextentity.api;

/// 排序方向：`orderBy(...)` 之后必须选方向。
///
/// @param <T> 根实体类型
/// @param <U> 结果类型
public interface OrderOperator<T, U> {

    /// 升序。
    ///
    /// @return 同一条查询的下一步；多列排序按调用顺序累积
    BaseWhereStep<T, U> asc();

    /// 降序。
    ///
    /// @return 同一条查询的下一步；多列排序按调用顺序累积
    BaseWhereStep<T, U> desc();
}
