package io.github.nextentity.api;

/// 条件更新的 `SET` 阶段：一次 `UPDATE` 可以设多列，设完再接 [UpdateWhereStep] 的 `where`。
///
/// ## 使用示例
///
/// ```java
/// int updated = repository.update()
///     .set(Employee::getStatus, "ARCHIVED")
///     .set(Employee::getUpdatedAt, Instant.now())
///     .where(Employee::getActive).eq(false)
///     .execute();
/// ```
///
/// 全部 `set` 必须写在 `where` 之前——`where` 返回 [UpdateWhereStep]，不再允许继续设列，
/// 免得"设了一半就执行"这种顺序歧义。
///
/// @param <T> 实体类型
/// @see Executable
public interface UpdateSetStep<T> extends UpdateWhereStep<T> {

    /// 设置一列的值（方法引用形态的路径）。
    ///
    /// @param path  列路径
    /// @param value 新值，可为 null（表示置空）
    /// @param <U>   列类型
    /// @return 本步骤，可继续 `set` 或 `where`
    <U> UpdateSetStep<T> set(PathRef<T, U> path, U value);

    /// 设置一列的值（[Path] 形态的路径）。
    ///
    /// 嵌套列（关联/`@Embedded`）只能用这种形态表达：`set(Path.of(X::getDept).get(Dept::getName), "A")`。
    /// 把关联外键置空也走这里：`set(Path.of(X::getDept).get(Dept::getId), null)`。
    ///
    /// @param path  列路径
    /// @param value 新值，可为 null（表示置空）
    /// @param <U>   列类型
    /// @return 本步骤，可继续 `set` 或 `where`
    <U> UpdateSetStep<T> set(Path<T, U> path, U value);
}
