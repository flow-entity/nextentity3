package io.github.nextentity.api;

/// 条件更新的 `WHERE` 阶段：条件写法与查询完全一致，最后用 [Executable#execute()] 执行。
///
/// 不写 `where` 就是无条件更新（整表），因此无条件更新要显式承担后果；
/// 需要"按主键更新一行"时优先用 `update(entity)`。
///
/// **写了 `where(...)` 但条件全被短路**（如 `eqIfNotNull(null)`）或只取到算子却没链 `eq`/`gt` 等，
/// 等价于不写 `where`——同样是整表更新。要「条件齐全才写、否则不动」，请调用方自行判断后再执行。
///
/// @param <T> 实体类型
public interface UpdateWhereStep<T> extends Whereable<T, UpdateWhereStep<T>>, Executable {}
