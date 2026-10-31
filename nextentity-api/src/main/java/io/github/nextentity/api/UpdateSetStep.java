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
    /// 合法的 SET 目标只有三种：本表标量列、`@Embedded` 的叶子列、`关联.@Id`
    /// （写本表外键列，值 `null` 即清外键）。后两种用 [EntityPath] 构造嵌套路径——
    /// [Path] 本身没有 `get`：
    ///
    /// ```java
    /// // @Embedded 叶子
    /// update.set(EntityPath.of(Employee::getAddress).get(Address::getCity), "Hangzhou");
    /// // 关联外键：写本表外键列，null 即清外键
    /// update.set(EntityPath.of(Employee::getDepartment).get(Department::getId), null);
    /// ```
    ///
    /// 其余形态（关联对象整体、嵌入对象整体、被关联表的列如 `department.name`、
    /// 三段及以上路径）不被支持，抛 `UnsupportedOperationException` 并点名路径
    /// （两个后端都拒绝，措辞不同不构成契约）。`@Column` / `@JoinColumn` 标了
    /// `updatable = false` 的只读列一律拒绝，同样点名路径。
    ///
    /// @param path  列路径
    /// @param value 新值，可为 null（表示置空）
    /// @param <U>   列类型
    /// @return 本步骤，可继续 `set` 或 `where`
    <U> UpdateSetStep<T> set(Path<T, U> path, U value);
}
