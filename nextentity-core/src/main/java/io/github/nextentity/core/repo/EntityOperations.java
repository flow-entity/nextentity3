package io.github.nextentity.core.repo;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.core.exec.PersistExecutor;
import io.github.nextentity.core.meta.EntityType;

/// 单个实体的操作入口：查询 + 写入 + 元数据。
///
/// 只保留两个真实消费者需要的成员：
/// - `AppVersionRepository`（`spring.AbstractRepository` 子类）只用 `query()`；
/// - point-mall 的 `AppRepositoryImpl`（B 方案重写后）用 `query()`、`PersistExecutor` 的四个写方法，
///   以及 `entityType().version()` 回写 `@Version`（`update`/`updateAll` 的乐观锁语义）。
///
/// 2.2.3 的条件 DSL（`update().set().where()` / `delete().where()` / `fetch(...)` / `toSubQuery()`）
/// 在 point-mall 零调用点，一律不进本接口。
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
