package io.github.nextentity.jpa.exec;

import io.github.nextentity.api.DeleteWhereStep;
import io.github.nextentity.api.UpdateSetStep;
import io.github.nextentity.core.builder.DeleteWhereStepBuilder;
import io.github.nextentity.core.builder.UpdateSetStepBuilder;
import io.github.nextentity.core.spec.Assignment;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.DeleteSpec;
import io.github.nextentity.core.spec.UpdateSpec;
import io.github.nextentity.jpa.compile.JpaCriteria;
import io.github.nextentity.jpa.meta.JpaMeta;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaDelete;
import jakarta.persistence.criteria.CriteriaUpdate;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import java.util.List;

/// 条件更新 / 条件删除的唯一实现点：一条 `UPDATE` / `DELETE` + `WHERE`，返回受影响行数。
///
/// 与 [`JpaPersistExecutor`] 拆开的原因有两个：`update(entity)` 是「按主键 + 旧版本值写回一行」
/// 的乐观锁语义，条件改删是「按任意条件批量改/删」的集合语义，混在一个类里两套口径会互相
/// 借用（例如误把 `verifyAffected` 的「必须恰好 1 行」套到批量改删上）；另外那个类的方法数
/// 已经顶到 PMD 的上限（12 条），继续堆方法必然触发 `TooManyMethods`。
///
/// @param <T> 实体类型
final class ConditionalWrite<T> {

    private final EntityManager entityManager;
    private final JpaMeta meta;
    private final Class<T> entityType;

    /// 创建条件改删的实现。
    ///
    /// @param entityManager 实体管理器，非 `null`
    /// @param meta          应用级 JPA 元模型
    /// @param entityType    本实现负责的实体类型
    ConditionalWrite(EntityManager entityManager, JpaMeta meta, Class<T> entityType) {
        this.entityManager = entityManager;
        this.meta = meta;
        this.entityType = entityType;
    }

    /// 条件更新的链式入口（`set(...)` / `where(...)` / `execute()` 的装配由 core 完成）。
    ///
    /// @return 条件更新步骤
    UpdateSetStep<T> updateStep() {
        return new UpdateSetStepBuilder<>(entityType, this::update);
    }

    /// 条件删除的链式入口（`where(...)` / `execute()` 的装配由 core 完成）。
    ///
    /// @return 条件删除步骤
    DeleteWhereStep<T> deleteStep() {
        return new DeleteWhereStepBuilder<>(entityType, this::delete);
    }

    /// 执行条件更新：一条 `UPDATE ... SET ... WHERE`，返回受影响行数。
    ///
    /// `set(...)` 的右值只接受字面量（core 的 `Assignment` 口径），`null` 写 `NULL`；
    /// SET 目标只有「本表列」与「关联.`@Id`（写本表外键列）」两种，其余形态显式拒绝，
    /// 判定见 [`#writableTarget(Root, AttributePath)`]。
    @SuppressWarnings({"unchecked", "rawtypes"})
    private int update(UpdateSpec spec) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaUpdate<T> update = builder.createCriteriaUpdate(entityType);
        Root<T> root = update.from(entityType);
        for (Assignment assignment : spec.assignments()) {
            update.set((Path) writableTarget(root, assignment.path()), assignment.value());
        }
        if (spec.where() != null) {
            update.where(new JpaCriteria(builder, root, meta).predicate(spec.where()));
        }
        return entityManager.createQuery(update).executeUpdate();
    }

    /// 执行条件删除：一条 `DELETE ... WHERE`，返回受影响行数。
    private int delete(DeleteSpec spec) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaDelete<T> delete = builder.createCriteriaDelete(entityType);
        Root<T> root = delete.from(entityType);
        if (spec.where() != null) {
            delete.where(new JpaCriteria(builder, root, meta).predicate(spec.where()));
        }
        return entityManager.createQuery(delete).executeUpdate();
    }

    /// 解析 SET 目标：只允许「本表列」与「关联.`@Id`（写本表外键列）」两种形态。
    ///
    /// 两种形态都是一段 `get` 到**本表列**：
    /// - `列`：本表标量列，按 `@Column(updatable = false)` 判可写；
    /// - `嵌入属性.叶子`：`@Embedded` 摊在本表的列，按叶子列的 `@Column(updatable = false)` 判可写；
    /// - `关联.目标@Id 属性名`：本表外键列，按关联的 `@JoinColumn(updatable = false)` 判可写。
    ///
    /// 3.1.0 之前是「逐段 `get` + 只校验首段可写」，两个后果都是静默写错列：`owner` 这类只读外键
    /// 用嵌套形态能绕过 `@JoinColumn(updatable=false)` 直接改列；`owner.name` 这类被关联表的列
    /// 会被当成本表同名列写掉（有同名列是数据破坏，没有则是 SQL 语法错）。其余形态
    /// （直接关联对象、嵌入对象整体、跨实体标量、三段及以上）一律显式拒绝，与 JDBC 后端对齐。
    ///
    /// @throws UnsupportedOperationException SET 目标不是本表列或关联.`@Id`
    private Path<?> writableTarget(Root<?> root, AttributePath path) {
        List<String> names = path.names();
        if (path.isDirect()) {
            return scalarTarget(root, path, names.getFirst());
        }
        if (names.size() != 2) {
            throw targetNotColumn(path);
        }
        String first = names.getFirst();
        String second = names.getLast();
        Class<?> entityType = path.entityType();
        if (meta.embedded(entityType, first)) {
            return embeddedLeafTarget(root, path, first, second);
        }
        if (meta.owningAssociation(entityType, first)) {
            return foreignKeyTarget(root, path, first, second);
        }
        throw targetNotColumn(path);
    }

    /// 单段路径：本表标量列。关联对象 / 嵌入对象整体不是「一个列」，显式拒绝。
    private Path<?> scalarTarget(Root<?> root, AttributePath path, String name) {
        Class<?> entityType = path.entityType();
        if (meta.embedded(entityType, name) || meta.association(entityType, name)) {
            // 关联对象 / 嵌入对象整体赋值：不是「一个列」，Hibernate 会展开成多列写入
            throw targetNotColumn(path);
        }
        requireUpdatable(meta.scalarUpdatable(entityType, name), path);
        return root.get(name);
    }

    /// `嵌入属性.叶子`：`@Embedded` 摊在本表的列；`嵌入属性.嵌入属性` 是整块赋值、
    /// `嵌入属性.关联对象` 是关联整体赋值，都不是一列，显式拒绝。
    private Path<?> embeddedLeafTarget(Root<?> root, AttributePath path, String first, String second) {
        Class<?> embeddedType = meta.attributeJavaType(path.entityType(), first);
        if (meta.embedded(embeddedType, second) || meta.association(embeddedType, second)) {
            // 「嵌入属性.嵌入属性」/「嵌入属性.关联对象」都是整块赋值，不是列
            throw targetNotColumn(path);
        }
        requireUpdatable(meta.embeddedColumnUpdatable(path.entityType(), first, second), path);
        return root.get(first).get(second);
    }

    /// `关联.目标@Id 属性名`：本表外键列；第二段不是目标 `@Id` 时是跨实体列，显式拒绝。
    private Path<?> foreignKeyTarget(Root<?> root, AttributePath path, String first, String second) {
        Class<?> entityType = path.entityType();
        if (!second.equals(meta.idName(meta.attributeJavaType(entityType, first)))) {
            throw targetNotColumn(path);
        }
        requireUpdatable(meta.foreignKeyUpdatable(entityType, first), path);
        return root.get(first).get(second);
    }

    /// SET 目标可写性校验。
    ///
    /// 文案与 JDBC 后端（`ConditionalSql.assignment`）**逐字一致**：同一调用打到两个后端上
    /// 必须拿到同一句话，否则一致性对拍只能断到"都抛了 UOE"这种弱结论。
    private static void requireUpdatable(boolean updatable, AttributePath path) {
        if (!updatable) {
            throw new UnsupportedOperationException("条件更新不支持 @Column(updatable=false) 列: " + describe(path));
        }
    }

    /// 非法 SET 目标的统一拒绝：与「只读列」分开报——前者是**路径语义**不支持，
    /// 后者是列标志禁止写，调用方要按不同原因处理。
    private static UnsupportedOperationException targetNotColumn(AttributePath path) {
        return new UnsupportedOperationException("条件更新 SET 目标只能是本表列或关联.id: " + describe(path));
    }

    /// 与 JDBC 的 `Assignment` 路径描述同格式：`实体全名.属性[.属性]`。
    private static String describe(AttributePath path) {
        return path.entityType().getName() + "." + String.join(".", path.names());
    }
}
