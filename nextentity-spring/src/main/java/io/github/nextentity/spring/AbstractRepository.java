package io.github.nextentity.spring;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.core.EntityOperationsFactory;
import io.github.nextentity.core.TypeCastUtil;
import io.github.nextentity.core.repo.EntityOperations;
import org.springframework.core.ResolvableType;

/// 仓储基类：把「按实体取操作入口」这件事从调用方手里拿掉，子类只需声明泛型。
///
/// 成员面只到真实调用点为止（point-mall 的 `AppVersionRepository`）：
/// 构造器注入 {@link EntityOperationsFactory} + {@link #query()}。
/// 2.2.3 里的 `findAll` / `count` / `insert` / `updateAll` / `delete` / `path(...)` 在 point-mall
/// 零调用点，写操作走 `@Transactional` + `core.PersistExecutor`，不在本类复制。
///
/// @param <T>  实体类型
/// @param <ID> 主键类型（仅用于子类声明的可读性，本类不需要它）
public abstract class AbstractRepository<T, ID> {

    private final EntityOperations<T> operations;

    /// 由子类泛型解析实体类型并取操作入口。
    ///
    /// @param factory 实体操作工厂（Spring 上下文里唯一的那个 bean）
    protected AbstractRepository(EntityOperationsFactory factory) {
        Class<T> entityType = TypeCastUtil.cast(
                ResolvableType.forClass(getClass()).as(AbstractRepository.class).resolveGeneric(0));
        this.operations = factory.operations(entityType);
    }

    /// 新建查询入口。
    ///
    /// @return 查询入口
    public EntityQuery<T> query() {
        return operations.query();
    }
}
