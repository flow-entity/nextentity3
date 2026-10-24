package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.core.EntityOperationsFactory;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.JpaEntityOperationsFactory;
import io.github.nextentity.testing.ItemStatus;
import io.github.nextentity.tests.jpa.support.HibernateHarness;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// 更新一行**不存在**的记录：必须抛标准 JPA 异常，且库里不许因此多出一行。
///
/// 专抓「`update` 被改成 upsert / `merge` / 先查再插」这类"更好用"的改写：本库的 `update` 就是
/// `UPDATE … WHERE id=? [AND version=?]`，影响行数 0 即报错（`JpaPersistExecutor.verifyAffected`）。
/// 一旦有人把它换成「查不到就插」，异常断言与「行数不变」断言会同时变红——只断异常类型时，
/// 一个先插后改的实现照样能抛出异常（插完再撞主键），但库里已经脏了。
///
/// 两种实体各一条：无 `@Version` 抛 `EntityNotFoundException`，有 `@Version` 抛
/// `OptimisticLockException`（同一个 `verifyAffected` 分支的两种出口，见 USAGE §10）。
class ItemMissingRowUpdateTest {

    /// 夹具里不存在的主键：更新它等于「改一行没有的行」。
    private static final long ABSENT_ID = 999L;

    private final HibernateHarness harness =
            HibernateHarness.start("item_missing_row", ItemEntity.class, VersionedEntity.class);

    private final EntityOperations<ItemEntity> plain;
    private final EntityOperations<VersionedEntity> versioned;

    ItemMissingRowUpdateTest() {
        EntityOperationsFactory factory =
                new JpaEntityOperationsFactory(harness.entityManager(), harness.entityManagerFactory());
        this.plain = factory.operations(ItemEntity.class);
        this.versioned = factory.operations(VersionedEntity.class);
    }

    @AfterEach
    void closeHarness() {
        harness.close();
    }

    /// 无 `@Version` 的实体：更新不存在的行 → `EntityNotFoundException`，消息点名实体与 id。
    @Test
    void updateAbsentRowWithoutVersionThrowsEntityNotFound() {
        harness.inTransaction(_ -> plain.insert(new ItemEntity(1L, "apple", 10, 10, null, null)));
        harness.entityManager().clear();

        assertThatThrownBy(() -> harness.inTransaction(_ -> plain.update(absentItem())))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("不存在: id=" + ABSENT_ID)
                .hasMessageContaining(ItemEntity.class.getName());

        rollbackActiveTransaction();
        harness.entityManager().clear();

        assertThat(plain.query().count()).isEqualTo(1L);
        assertThat(plain.query().where(ItemEntity::id).eq(ABSENT_ID).list()).isEmpty();
    }

    /// 有 `@Version` 的实体：同一操作 → `OptimisticLockException`，消息额外带**给了的版本值**。
    ///
    /// 「行不存在」与「版本过期」在本库共用一条消息前缀，靠 `version=` 区分；
    /// 少断 `version=` 时，一个把版本条件漏进 `WHERE` 的实现（只按 id 更新）也能让冲突分支跑通，
    /// 但这条用例会因为消息里丢了版本值而红。
    @Test
    void updateAbsentRowWithVersionThrowsOptimisticLock() {
        harness.inTransaction(_ -> versioned.insert(new VersionedEntity(1L, "one")));
        harness.entityManager().clear();

        assertThatThrownBy(
                        () -> harness.inTransaction(_ -> versioned.update(new VersionedEntity(ABSENT_ID, "ghost", 0L))))
                .isInstanceOf(OptimisticLockException.class)
                .hasMessageContaining("不存在: id=" + ABSENT_ID + ", version=0")
                .hasMessageContaining(VersionedEntity.class.getName());

        rollbackActiveTransaction();
        harness.entityManager().clear();

        assertThat(versioned.query().count()).isEqualTo(1L);
        assertThat(versioned.query().where(VersionedEntity::id).eq(ABSENT_ID).list())
                .isEmpty();
    }

    private ItemEntity absentItem() {
        return new ItemEntity(ABSENT_ID, "ghost", 1, 1, "g", ItemStatus.NEW);
    }

    /// `HibernateHarness.inTransaction` 在异常时**不**回滚（它只是不提交），这里替测试收尾。
    private void rollbackActiveTransaction() {
        if (harness.entityManager().getTransaction().isActive()) {
            harness.entityManager().getTransaction().rollback();
        }
    }
}
