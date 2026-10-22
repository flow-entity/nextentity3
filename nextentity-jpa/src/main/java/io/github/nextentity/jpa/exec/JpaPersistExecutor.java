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
/// 与 2.2.3 的差异（reviewer R-g3 记录）：① 异常用标准 JPA 异常（`OptimisticLockException` /
/// `EntityNotFoundException`）替代 2.2.3 的自定义异常；② `@Version` 支持 Integer 与 Long
/// （2.2.3 只支持 Integer，其它类型会 `ClassCastException`），point-mall 的 27 处版本列全是 `integer`。
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
        setValues(entityMeta, entity, update, root, versionValue);
        update.where(whereClause(builder, root, id, version, idValue, versionValue));
        if (version != null) {
            entityManager.detach(entity);
        }

        int affected = entityManager.createQuery(update).executeUpdate();
        verifyAffected(affected, version, idValue, versionValue);
        if (version != null) {
            version.set(entity, nextVersion(versionValue));
        }
    }

    /// 影响行数必须恰好 1：有 `@Version` 时是乐观锁冲突（`OptimisticLockException`），
    /// 否则是行不存在。异常类型是 3.x 的决定：**用标准 JPA 异常替代 2.2.3 的自定义异常**
    /// （2.2.3 抛 `NextEntityException("Entity not found with id: …")`），point-mall 无 catch 依赖。
    private void verifyAffected(int affected, EntityAttribute version, Object idValue, Object versionValue) {
        if (affected == 1) {
            return;
        }
        String message = "实体 " + entityType.getName() + " 不存在: id=" + idValue;
        if (version == null) {
            throw new EntityNotFoundException(message);
        }
        throw new OptimisticLockException(message + ", version=" + versionValue);
    }

    /// 把实体的基本属性写进 SET 子句：跳过主键与关联，版本列写「旧值 + 1」。
    private void setValues(
            JpaEntityType<T> entityMeta, T entity, CriteriaUpdate<T> update, Root<T> root, Object versionValue) {
        EntityAttribute id = entityMeta.id();
        EntityAttribute version = entityMeta.version();
        for (Map.Entry<String, EntityAttribute> attribute :
                entityMeta.attributes().entrySet()) {
            String name = attribute.getKey();
            if (name.equals(id.name()) || meta.association(entityType, name)) {
                continue;
            }
            boolean versionColumn = version != null && name.equals(version.name());
            update.set(
                    root.get(name),
                    versionColumn
                            ? nextVersion(versionValue)
                            : attribute.getValue().get(entity));
        }
    }

    /// 主键条件；有 `@Version` 时再叠一个版本条件（2.2.3 的乐观锁写法）。
    private Predicate whereClause(
            CriteriaBuilder builder,
            Root<T> root,
            EntityAttribute id,
            EntityAttribute version,
            Object idValue,
            Object versionValue) {
        Predicate byId = builder.equal(root.get(id.name()), idValue);
        if (version == null) {
            return byId;
        }
        return builder.and(byId, builder.equal(root.get(version.name()), versionValue));
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
