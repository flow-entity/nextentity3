package io.github.nextentity.jpa.exec;

import io.github.nextentity.api.DeleteWhereStep;
import io.github.nextentity.api.UpdateSetStep;
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

/// JPA 写入执行器：插入用 `persist`，更新用带版本条件的 `CriteriaUpdate`，条件更新/删除用
/// `CriteriaUpdate` / `CriteriaDelete`。
///
/// 语义对齐 2.2.3（`JpaPersistExecutor`）：
/// - `insert` / `insertAll` 逐条 `persist`，`@Version` 初值由 Hibernate 在 flush 时写；
/// - `update` / `updateAll` 按主键（有 `@Version` 时再加版本条件）批量 UPDATE，版本列写成
///   「旧值 + 1」，影响行数 ≠ 1 抛乐观锁异常，成功后把新版本号回写到调用方对象上；
/// - 实体已在持久化上下文时走**合并语义**：flush 当前持久化上下文（见 [`#update(Object)`]），
///   不再静默早退——「先 insert 再 update」的第二次写入不会因为 `contains` 为真而被吞掉；
/// - 单值关联（`@ManyToOne` 与 owning 侧 `@OneToOne`）按关联对象写外键列（Hibernate 从关联
///   对象取 `@Id`），键值 `null` 写 `NULL`；集合关联与 `@OneToOne(mappedBy=...)` 反侧没有
///   本表外键列，仍不写（CHANGELOG 3.1.0 已披露）。
///
/// 与 2.2.3 的差异：① 异常用标准 JPA 异常（`OptimisticLockException` /
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
    /// @param entityManager 实体管理器，非 `null`（事务绑定的那个代理）
    /// @param meta          应用级 JPA 元模型
    /// @param entityType    本执行器负责的实体类型
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

    /// 更新单个实体。
    ///
    /// 实体已在持久化上下文时**flush 整个持久化上下文**（不只是这一个实体）：flush 是 JPA
    /// 的上下文级操作，其它脏实体也会一起刷下去——这是语义，不是副作用失控。这样「这次
    /// update 有没有真的落库」「乐观锁冲突 / 行已被删」在调用点就能看到，而不是留给提交时。
    ///
    /// @param entity 待更新实体，非 `null`；成功后新 `@Version` 回写到该对象
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

    /// 条件更新入口（`set(...)` / `where(...)` / `execute()`），实现在 {@link ConditionalWrite}。
    @Override
    public UpdateSetStep<T> update() {
        return new ConditionalWrite<>(entityManager, meta, entityType).updateStep();
    }

    /// 条件删除入口（`where(...)` / `execute()`），实现在 {@link ConditionalWrite}。
    @Override
    public DeleteWhereStep<T> delete() {
        return new ConditionalWrite<>(entityManager, meta, entityType).deleteStep();
    }

    private void updateOne(JpaEntityType<T> entityMeta, T entity) {
        if (entityManager.contains(entity)) {
            // 合并语义：托管实体的写入由脏检查承担。立刻 flush，让挂起改动落库、让版本冲突 /
            // 行缺失在调用点抛出——原先的 `return` 会把「这次 update 到底写没写」静默留给提交。
            entityManager.flush();
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

    /// 把实体的标量属性与单值关联写进 SET 子句：跳过主键与写侧标记为只读的列，版本列写「旧值 + 1」。
    ///
    /// 只读按注解判：标量看 `@Column(updatable = false)`、关联外键看 `@JoinColumn(updatable = false)`，
    /// 与 JDBC 后端 `WriteSql.appendScalars` / `WriteSql.associationColumn` 同口径——**只按 flags 判，
    /// 不做列名去重**：同一列既被标量字段又被关联映射时（legacy `Employee` 的 `departmentId`），关联侧
    /// 因 `updatable = false` 被跳过，SET 里天然只出现一次；两边都可写的同列映射是非法映射，Hibernate
    /// 自己的 INSERT 会先报重复列，不该由这里猜命名策略去兜。
    ///
    /// 单值关联的写法是 `set(root.get(关联), 关联对象)`：Hibernate 从关联对象取 `@Id` 写外键列，
    /// 值为 `null` 时写 `NULL`（JPA 侧 dirty checking 本来就能置空，不为了对齐 JDBC 的
    /// 「null 跳过列」而弱化这里的行为）。
    private void setValues(
            JpaEntityType<T> entityMeta, T entity, CriteriaUpdate<T> update, Root<T> root, Object versionValue) {
        EntityAttribute id = entityMeta.id();
        EntityAttribute version = entityMeta.version();
        for (Map.Entry<String, EntityAttribute> attribute :
                entityMeta.attributes().entrySet()) {
            String name = attribute.getKey();
            if (name.equals(id.name())) {
                continue;
            }
            if (meta.association(entityType, name)) {
                if (!meta.owningAssociation(entityType, name)) {
                    // 集合关联（另一张表 / 连接表）与 @OneToOne(mappedBy) 反侧（外键在对端表）
                    continue;
                }
                if (!entityMeta.updatable(name)) {
                    // @JoinColumn(updatable = false)：外键列不参与 UPDATE
                    continue;
                }
                update.set(root.get(name), attribute.getValue().get(entity));
                continue;
            }
            if (!entityMeta.updatable(name)) {
                // @Column(updatable = false)：写侧跳过，读回不受影响
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

    /// `@Version` 递增的**唯一**实现点：四个分支各有一条用例（`JpaPersistExecutorNextVersionTest`）。
    ///
    /// 包私有而不是私有：`default` 分支要抛 `UnsupportedOperationException`，而「版本列是第三种
    /// 类型」的实体在测试实体集里不存在，走不到这里。按红线「契约拒绝必须钉住」，这条拒绝分支
    /// 必须有 `assertThatThrownBy` 用例，所以放开到包内可见。
    ///
    /// @param versionValue 旧版本值，可为 `null`
    /// @return 新版本值
    static Object nextVersion(Object versionValue) {
        return switch (versionValue) {
            case null -> 0;
            case Integer current -> current + 1;
            case Long current -> current + 1;
            default ->
                throw new UnsupportedOperationException("@Version 只支持 Integer / Long: "
                        + versionValue.getClass().getName());
        };
    }
}
