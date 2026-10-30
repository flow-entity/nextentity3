package io.github.nextentity.core.repo;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.core.exec.PersistExecutor;
import io.github.nextentity.core.meta.EntityType;

/// 单个实体的操作入口：查询 + 写入 + 元数据。
///
/// 成员来自两处：
/// - 本接口自己声明 `query()` 与 `entityType()`；
/// - [PersistExecutor] 提供实体级写入（`insert` / `insertAll` / `update` / `updateAll`）与
///   条件写入入口（`update().set().where()` / `delete().where()`）。
///
/// 3.1 起 2.2.3 的条件更新/删除 DSL 加性恢复（`fetch(...)` 在 `EntityQuery` 上，`toSubQuery()` 仍不提供）。
///
/// @param <T> 实体类型
public interface EntityOperations<T> extends PersistExecutor<T> {

    /// 新建一个查询构建器（每次调用返回独立实例，链式状态互不影响）。
    ///
    /// @return 新的查询入口，不是共享实例
    EntityQuery<T> query();

    /// 该实体的元数据（主键、`@Version`）。
    ///
    /// @return 缓存的元数据：同一实体多次调用返回同一实例
    EntityType<T> entityType();
}
