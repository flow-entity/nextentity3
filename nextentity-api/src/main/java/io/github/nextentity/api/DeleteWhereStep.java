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
/// @param <T> 实体类型
public interface DeleteWhereStep<T> extends Whereable<T, DeleteWhereStep<T>>, Executable {}
