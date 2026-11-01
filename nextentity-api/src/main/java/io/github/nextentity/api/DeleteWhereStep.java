package io.github.nextentity.api;

/// 条件删除：条件写法与查询完全一致，最后用 [Executable#execute()] 执行。
///
/// ## 使用示例
///
/// ```java
/// int deleted = repository.delete()
///     .where(Employee::getStatus).eq("INACTIVE")
///     .execute();
/// ```
///
/// 不写 `where` 就是无条件删除（整表），因此无条件删除要显式承担后果。
///
/// **写了 `where(...)` 但条件全被短路**（如 `eqIfNotNull(null)`）或只取到算子却没链 `eq`/`gt` 等，
/// 等价于不写 `where`——同样是整表删除。要「条件齐全才删、否则不动」，请调用方自行判断后再执行。
///
/// @param <T> 实体类型
public interface DeleteWhereStep<T> extends Whereable<T, DeleteWhereStep<T>>, Executable {}
