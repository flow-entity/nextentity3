package io.github.nextentity.jpa.exec;

import io.github.nextentity.core.exec.PersistExecutor;
import io.github.nextentity.core.meta.EntityAttribute;
import io.github.nextentity.jpa.meta.JpaEntityType;
import io.github.nextentity.jpa.meta.JpaMeta;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaUpdate;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.Map;

/// JPA 写入执行器：插入用 `persist`，更新用带版本条件的 `CriteriaUpdate`。
///
/// 语义对齐 2.2.3（`JpaPersistExecutor`）：
/// - `insert` / `insertAll` 逐条 `persist`，`@Version` 初值由 Hibernate 在 flush 时写；
/// - `update` / `updateAll` 不做 `merge`：按主键（有 `@Version` 时再加版本条件）批量 UPDATE，
///   版本列写成「旧值 + 1」，影响行数 ≠ 1 抛乐观锁异常，成功后把新版本号回写到调用方对象上；
/// - 已在持久化上下文的实体跳过（交给脏检查）。
///
/// 关联属性不参与 SET（2.2.3 同样只更新基本属性）：point-mall 的外键靠实体列更新，
/// 不在本接缝的调用点里。
///
/// @param <T> 实体类型
public final class JpaPersistExecutor<T> implements PersistExecutor<T> {

    private final EntityManager entityManager;
    private final JpaMeta meta;
    private final Class<T> entityType;

    /// 创建写入执行器。
    ///
    /// @param entityManager 实体管理器
    /// @param meta          JPA 元模型
    /// @param entityType    实体类型
    public JpaPersistExecutor(EntityManager entityManager, JpaMeta meta, Class<T> entityType) {
        this.entityManager = entityManager;
        this.meta = meta;
        this.entityType = entityType;
    }

    @Override
    public void insert(T entity) {
        entityManager.persist(entity);
    }

    @Override
    public void insertAll(Iterable<T> entities) {
        for (T entity : entities) {
            entityManager.persist(entity);
        }
    }

    @Override
    public void update(T entity) {
        updateAll(List.of(entity));
    }

    @Override
    public void updateAll(Iterable<T> entities) {
        JpaEntityType<T> entityMeta = meta.entityType(entityType);
        for (T entity : entities) {
            updateOne(entityMeta, entity);
        }
    }

    private void updateOne(JpaEntityType<T> entityMeta, T entity) {
        if (entityManager.contains(entity)) {
            return;
        }
        EntityAttribute id = entityMeta.id();
        EntityAttribute version = entityMeta.version();
        Object idValue = id.get(entity);
        Object versionValue = version == null ? null : version.get(entity);

        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaUpdate<T> update = builder.createCriteriaUpdate(entityType);
        Root<T> root = update.from(entityType);
        for (Map.Entry<String, EntityAttribute> attribute :
                entityMeta.attributes().entrySet()) {
            String name = attribute.getKey();
            if (name.equals(id.name()) || meta.association(entityType, name)) {
                continue;
            }
            if (version != null && name.equals(version.name())) {
                update.set(root.get(name), nextVersion(versionValue));
            } else {
                update.set(root.get(name), attribute.getValue().get(entity));
            }
        }
        Predicate where = builder.equal(root.get(id.name()), idValue);
        if (version != null) {
            where = builder.and(where, builder.equal(root.get(version.name()), versionValue));
            entityManager.detach(entity);
        }
        update.where(where);

        int affected = entityManager.createQuery(update).executeUpdate();
        if (affected != 1) {
            throw version == null
                    ? new EntityNotFoundException("实体 " + entityType.getName() + " 不存在: id=" + idValue)
                    : new OptimisticLockException(
                            "实体 " + entityType.getName() + " 版本冲突或不存在: id=" + idValue + ", version=" + versionValue);
        }
        if (version != null) {
            version.set(entity, nextVersion(versionValue));
        }
    }

    private static Object nextVersion(Object versionValue) {
        if (versionValue == null) {
            return 0;
        }
        if (versionValue instanceof Integer current) {
            return current + 1;
        }
        if (versionValue instanceof Long current) {
            return current + 1;
        }
        throw new UnsupportedOperationException(
                "@Version 只支持 Integer / Long: " + versionValue.getClass().getName());
    }
}
