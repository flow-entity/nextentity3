package io.github.nextentity.core.meta;

/// 实体元数据：后端无关的只读视图。
///
/// 只认 JPA 映射注解（`@Id`、`@Version`），不认任何后端类型——JPA 实现走
/// `EntityManager.getMetamodel()`，将来的 SQL 实现走注解扫描，调用方无感。
///
/// @param <T> 实体类型
public interface EntityType<T> {

    /// 实体 Java 类型。
    Class<T> type();

    /// 主键属性。
    EntityAttribute id();

    /// `@Version` 属性；没有版本字段时返回 `null`。
    ///
    /// 乐观锁语义依赖它：`update`/`updateAll` 合并后要把新版本号回写到调用方持有的对象，
    /// 否则调用方手里的实体版本号是旧的，下一次更新会撞乐观锁异常（point-mall 现有行为）。
    EntityAttribute version();
}
