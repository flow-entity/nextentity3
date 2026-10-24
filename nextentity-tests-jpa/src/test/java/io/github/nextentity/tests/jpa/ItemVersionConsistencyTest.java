package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.JpaEntityOperationsFactory;
import io.github.nextentity.tests.jpa.support.HibernateHarness;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// `@Version` 乐观锁：`update` 必须让版本号自增，且用过期版本号更新时抛 `OptimisticLockException`。
///
/// 真实调用点：point-mall 的 `AppRepositoryImpl.update/updateAll` 依赖 `EntityType.version()` 回写
/// 版本号；`JpaPersistExecutor` 的 UPDATE 带版本条件（`WHERE id=? AND version=?`），影响行数 0 即冲突。
class ItemVersionConsistencyTest {

    private final HibernateHarness harness = HibernateHarness.start("item_version", VersionedEntity.class);
    private final EntityOperations<VersionedEntity> operations;

    ItemVersionConsistencyTest() {
        this.operations = new JpaEntityOperationsFactory(harness.entityManager(), harness.entityManagerFactory())
                .operations(VersionedEntity.class);
    }

    @AfterEach
    void closeHarness() {
        harness.close();
    }

    /// 插入 → 一次更新使版本号自增 → 用旧版本号再更新则冲突，且冲突**没有副作用**。
    ///
    /// 只断 `isInstanceOf(OptimisticLockException.class)` 时，「版本条件丢了」（`WHERE` 只剩 id）
    /// 与「版本条件在」两种实现都能过：前者影响 1 行、根本不抛异常——所以真正区分它们的是
    /// **消息里的陈旧版本值**。冲突后的两处副作用也逐项钉住：
    /// - 调用方对象的 `@Version` 不被回写（否则调用方拿着一个从未落库的版本号去重试，永远撞锁）；
    /// - 库里那一行既没改名也没改版本号（0 行的 UPDATE 若被误判成功，`published` 会变成 `stale`）。
    @Test
    void updateBumpsVersionAndStaleUpdateFails() {
        inTransaction(_ -> operations.insert(new VersionedEntity(1L, "draft")));
        long versionAfterInsert = currentVersion(1L);

        inTransaction(_ -> operations.update(new VersionedEntity(1L, "published", versionAfterInsert)));
        long versionAfterUpdate = currentVersion(1L);

        assertThat(versionAfterUpdate).isGreaterThan(versionAfterInsert);

        VersionedEntity stale = new VersionedEntity(1L, "stale", versionAfterInsert);
        assertThatThrownBy(() -> inTransaction(_ -> operations.update(stale)))
                .isInstanceOf(OptimisticLockException.class)
                .hasMessageContaining("不存在: id=1")
                .hasMessageContaining("version=" + versionAfterInsert);

        assertThat(stale.version()).describedAs("冲突时不许把新版本号回写到调用方对象").isEqualTo(versionAfterInsert);

        rollbackActiveTransaction();
        harness.entityManager().clear();
        assertThat(row(1L).name()).describedAs("冲突的更新不许落库").isEqualTo("published");
        assertThat(row(1L).version()).isEqualTo(versionAfterUpdate);
    }

    /// 成功更新后，调用方手里的对象拿到新版本号（`@Version` 回写契约）。
    @Test
    void updateWritesBackNewVersionToCallerObject() {
        inTransaction(_ -> operations.insert(new VersionedEntity(2L, "draft")));
        VersionedEntity detached = new VersionedEntity(2L, "published", currentVersion(2L));

        inTransaction(_ -> operations.update(detached));

        assertThat(detached.version()).isEqualTo(currentVersion(2L));
    }

    private void inTransaction(Consumer<EntityManager> work) {
        harness.inTransaction(work);
        harness.entityManager().clear();
    }

    /// `HibernateHarness.inTransaction` 异常时不回滚（只是不提交），冲突用例得自己收尾。
    private void rollbackActiveTransaction() {
        if (harness.entityManager().getTransaction().isActive()) {
            harness.entityManager().getTransaction().rollback();
        }
    }

    private VersionedEntity row(long id) {
        return harness.entityManager()
                .createQuery("select v from VersionedEntity v where v.id = :id", VersionedEntity.class)
                .setParameter("id", id)
                .getSingleResult();
    }

    private long currentVersion(long id) {
        return harness.entityManager()
                .createQuery("select v.version from VersionedEntity v where v.id = :id", Long.class)
                .setParameter("id", id)
                .getSingleResult();
    }
}
