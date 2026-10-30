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
    /// SET 目标逐段 `get`、不走 `join`——`CriteriaUpdate` 没有 join 语义，关联段只用于取
    /// 本表外键列（`dept.id` 就是 `dept_id`）。
    @SuppressWarnings({"unchecked", "rawtypes"})
    private int update(UpdateSpec spec) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaUpdate<T> update = builder.createCriteriaUpdate(entityType);
        Root<T> root = update.from(entityType);
        for (Assignment assignment : spec.assignments()) {
            update.set((Path) assignmentTarget(root, assignment.path()), assignment.value());
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

    /// 条件更新的 SET 目标：逐段 `get`（不 join，也不要 `JpaPaths` 的 LEFT JOIN）。
    private static Path<?> assignmentTarget(Root<?> root, AttributePath path) {
        Path<?> target = root;
        for (String name : path.names()) {
            target = target.get(name);
        }
        return target;
    }
}
