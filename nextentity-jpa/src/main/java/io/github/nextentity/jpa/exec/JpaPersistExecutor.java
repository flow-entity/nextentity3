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
import jakarta.persistence.criteria.Path;
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
///   本表外键列，仍不写（3.1.0 起即如此）。
///
/// 与 2.2.3 的差异：① 异常用标准 JPA 异常（`OptimisticLockException` /
/// `EntityNotFoundException`）替代 2.2.3 的自定义异常；② `@Version` 支持 Integer 与 Long
/// （2.2.3 只支持 Integer，其它类型会 `ClassCastException`），point-mall 的 27 处版本列全是 `integer`。
///
/// @param <T> 实体类型
@SuppressWarnings("PMD.TooManyMethods") // 方法数由 PersistExecutor 契约面 + 版本自增的类型上界守卫决定，不是上帝类
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
        if (!setValues(entityMeta, entity, update, root, versionValue)) {
            // 与 JDBC 后端 WriteSql.update 同口径显式拒绝：空 SET 会被 provider 当成内部错误
            // （Hibernate 抛 IllegalArgumentException("No assignments specified as part of UPDATE criteria")）。
            throw new UnsupportedOperationException("实体 " + entityType.getName()
                    + " 没有可写标量列，update 无法生成 SET 子句：可写标量列都带"
                    + " @Column(updatable=false)，也没有 @Version 列可以自增；请至少保留一个可更新列");
        }
        update.where(whereClause(builder, root, id, version, idValue, versionValue));

        int affected = entityManager.createQuery(update).executeUpdate();
        verifyAffected(affected, version, idValue, versionValue);
        if (version != null) {
            version.set(entity, nextVersion(versionValue, version.type()));
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

    /// 把实体的标量属性、单值关联与 `@Embedded` 叶子列写进 SET 子句：跳过主键与写侧标记为只读的列，
    /// 版本列写「旧值 + 1」。
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
    /// @return 是否真的写入了至少一条赋值；`false` 表示实体的全部列都不可写（调用方据此显式拒绝）
    private boolean setValues(
            JpaEntityType<T> entityMeta, T entity, CriteriaUpdate<T> update, Root<T> root, Object versionValue) {
        EntityAttribute id = entityMeta.id();
        EntityAttribute version = entityMeta.version();
        boolean assigned = false;
        for (Map.Entry<String, EntityAttribute> attribute :
                entityMeta.attributes().entrySet()) {
            String name = attribute.getKey();
            if (name.equals(id.name())) {
                continue;
            }
            // 同一实例：JpaEntityType.of 把同一个 JpaEntityAttribute 放进 attributes 表与 version()，
            // 故这里判元数据实例同一性，而不是再读一次实体属性名/版本号（避免多一个判定与 NPE 面）。
            boolean versionColumn = attribute.getValue() == version;
            if (!versionColumn && !entityMeta.updatable(name)) {
                // @Column / @JoinColumn(updatable = false)：写侧跳过，读回不受影响。
                // 嵌入叶子的覆盖级只读在 setEmbedded 内按叶子判。
                continue;
            }
            if (meta.association(entityType, name)) {
                if (!meta.owningAssociation(entityType, name)) {
                    // 集合关联（另一张表 / 连接表）与 @OneToOne(mappedBy) 反侧（外键在对端表）
                    continue;
                }
                update.set(root.get(name), attribute.getValue().get(entity));
                assigned = true;
                continue;
            }
            if (meta.embedded(entityType, name)) {
                // @Embedded 必须逐叶子列写：整对象 set 会让 Hibernate 展开全部嵌入列，
                // 连 @Column(updatable = false) 的叶子一起写（3.1.0 的只读列修复漏了这一层）。
                assigned |= setEmbedded(
                        update,
                        root.get(name),
                        meta.attributeJavaType(entityType, name),
                        name,
                        "",
                        attribute.getValue().get(entity));
                continue;
            }
            // 版本列例外：版本自增是这一层的乐观锁职责，@Column(updatable = false) 不能把它挡在
            // SET 外（JDBC 后端 WriteSql.update 同样无条件写版本列），否则库里版本停住而内存版本 +1。
            update.set(
                    root.get(name),
                    versionColumn
                            ? nextVersion(versionValue, version.type())
                            : attribute.getValue().get(entity));
            assigned = true;
        }
        return assigned;
    }

    /// 把 `@Embedded` 摊成逐叶子列的 `SET`。
    ///
    /// 整对象 `set(root.get(inner), value)` 会被 Hibernate 展开成**全部**嵌入列，`@Column(updatable
    /// = false)` 的叶子也一起进 `SET`（3.1.0 的只读列修复只覆盖了顶层标量）。这里按叶子逐列写：
    /// 只读叶子跳过，嵌入对象整体为 `null` 时可写叶子写 `NULL`，嵌套 `@Embedded` 递归展开。
    ///
    /// 只读按**覆盖后的生效列**判（`JpaMeta.embeddedColumnUpdatable`）：覆盖声明在根 `@Embedded`
    /// 属性上，故 `attribute` 是根属性名、`path` 是从根起的点号路径，嵌套层共用同一份覆盖上下文。
    /// @return 是否真的写入了至少一条叶子列
    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean setEmbedded(
            CriteriaUpdate<T> update,
            Path<?> holder,
            Class<?> embeddedType,
            String attribute,
            String path,
            Object value) {
        boolean assigned = false;
        for (String leaf : meta.attributeNames(embeddedType)) {
            Object leafValue = meta.read(embeddedType, leaf, value);
            String leafPath = path + leaf;
            if (meta.association(embeddedType, leaf)) {
                // 嵌入里的单值关联与顶层关联同形处理：按关联对象写本表外键列、null 写 NULL，
                // 集合关联与 @OneToOne(mappedBy) 反侧（外键不在本表）跳过。
                if (!meta.owningAssociation(embeddedType, leaf)) {
                    continue;
                }
                if (!meta.embeddedForeignKeyUpdatable(entityType, attribute, leafPath)) {
                    // 嵌入里的只读外键：字段上的 @JoinColumn(updatable = false)，
                    // 或实体侧 @AssociationOverride(joinColumns = @JoinColumn(updatable = false)) 覆盖出来的
                    continue;
                }
                update.set((Path) holder.get(leaf), leafValue);
                assigned = true;
                continue;
            }
            if (meta.embedded(embeddedType, leaf)) {
                assigned |= setEmbedded(
                        update,
                        holder.get(leaf),
                        meta.attributeJavaType(embeddedType, leaf),
                        attribute,
                        leafPath + '.',
                        leafValue);
                continue;
            }
            if (!meta.embeddedColumnUpdatable(entityType, attribute, leafPath)) {
                // 嵌入内部的只读列：叶子自己的 @Column(updatable = false)，或实体侧
                // @AttributeOverride(column = @Column(updatable = false)) 覆盖出来的只读列
                continue;
            }
            update.set((Path) holder.get(leaf), leafValue);
            assigned = true;
        }
        return assigned;
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

    /// `@Version` 递增的**唯一**实现点：三个类型分支各有一条用例（`JpaPersistExecutorNextVersionTest`）。
    ///
    /// 包私有而不是私有：拒绝分支要抛 `UnsupportedOperationException`，而「版本列是第四种
    /// 类型」的实体在测试实体集里不存在，走不到这里。按红线「契约拒绝必须钉住」，这条拒绝分支
    /// 必须有 `assertThatThrownBy` 用例，所以放开到包内可见。
    ///
    /// 按**属性类型**分派而不是按旧值分派：`null` 起步值必须与属性类型同型（`Short` 字段拿到
    /// `Integer 0` 会在 `EntityAttribute.set` 抛 `IllegalArgumentException`），且类型集与 JDBC 后端
    /// `JdbcTypes.nextVersion` / `JdbcColumns.VERSION_TYPES` 保持一致（int/Integer、long/Long、
    /// short/Short）。
    ///
    /// 到类型上界显式拒绝：回绕后的版本号比旧值小，谓词 `and version = ?` 从此再也匹配不上，
    /// 乐观锁静默失效——比抛异常危险得多。
    ///
    /// @param versionValue 旧版本值，可为 `null`
    /// @param type 版本属性的 Java 类型
    /// @return 新版本值
    static Object nextVersion(Object versionValue, Class<?> type) {
        if (type == Integer.class || type == int.class) {
            return (int) increment(versionValue, Integer.MAX_VALUE, "Integer");
        }
        if (type == Long.class || type == long.class) {
            return increment(versionValue, Long.MAX_VALUE, "Long");
        }
        if (type == Short.class || type == short.class) {
            return (short) increment(versionValue, Short.MAX_VALUE, "Short");
        }
        throw new UnsupportedOperationException("@Version 只支持 Integer / Long / Short（含原始类型）: " + type.getName());
    }

    private static long increment(Object versionValue, long max, String typeName) {
        if (versionValue == null) {
            return 0L;
        }
        long current = ((Number) versionValue).longValue();
        if (current == max) {
            throw new UnsupportedOperationException(
                    "@Version 已到 " + typeName + " 上界 " + max + "：再 +1 会回绕成负数，乐观锁将永远通过；请改用更大的版本类型");
        }
        return current + 1;
    }
}
