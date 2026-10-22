package io.github.nextentity.core;

import io.github.nextentity.core.repo.EntityOperations;

/// 按实体类型取操作入口的工厂：`spring` 模块把它注册成**唯一**一个 bean。
///
/// 包名与类型名是硬约定：point-mall 的 `AppVersionRepository:12` 直接
/// `import io.github.nextentity.core.EntityOperationsFactory` 并构造器注入给
/// `spring.AbstractRepository`（该文件属 D2「零改动」范围），因此这个类型必须叫这个名字、
/// 必须在这个包。实现由后端模块提供（JPA 侧一个），`spring` 只负责注册 bean。
public interface EntityOperationsFactory {

    /// 取指定实体的操作入口。
    ///
    /// @param entityClass 被映射的实体类型，非 `null`；未映射时抛 `IllegalArgumentException`
    /// @param <T>         实体类型参数
    /// @return 该实体的操作入口
    <T> EntityOperations<T> operations(Class<T> entityClass);
}
