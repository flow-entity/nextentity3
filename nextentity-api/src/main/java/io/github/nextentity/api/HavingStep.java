package io.github.nextentity.api;

/// 分组之后的步骤：只为承载后续 `orderBy` / `slice` / 终止操作而存在。
///
/// 2.2.3 的 `having(...)` 在 point-mall 零调用点，因此这里不声明它。
///
/// @param <T> 根实体类型
/// @param <U> 结果类型
public interface HavingStep<T, U> extends BaseWhereStep<T, U> {}
