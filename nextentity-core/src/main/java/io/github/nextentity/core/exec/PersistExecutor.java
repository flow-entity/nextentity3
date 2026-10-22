package io.github.nextentity.core.exec;

/// 写入执行器接缝：按实体做增改，后端负责乐观锁与批量。
///
/// 方法集合按调用点裁剪：point-mall 的 `AppRepositoryImpl` 只委托 `insert` / `insertAll` /
/// `update` / `updateAll` 四个；它的 `delete*` 来自 Spring Data `JpaRepository`，与本接缝无关。
///
/// `update` / `updateAll` 的语义约定：合并后必须把新的 `@Version` 值回写到调用方持有的对象上
/// （见 {@link io.github.nextentity.core.meta.EntityType#version()}），否则调用方的实体带着旧版本号，
/// 下一次更新会撞乐观锁异常。
///
/// @param <T> 实体类型
public interface PersistExecutor<T> {

    /// 插入单个实体。
    ///
    /// @param entity 待插入实体，非 `null`；主键何时可见由后端的生成策略决定
    void insert(T entity);

    /// 批量插入。
    ///
    /// @param entities 实体集合，非 `null`；逐条写入，空集合不产生语句
    void insertAll(Iterable<T> entities);

    /// 更新单个实体。
    ///
    /// @param entity 待更新实体，非 `null`；成功后新 `@Version` 回写到该对象
    void update(T entity);

    /// 批量更新。
    ///
    /// @param entities 实体集合，非 `null`；每个元素都要回写新 `@Version`
    void updateAll(Iterable<T> entities);
}
