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

    /// 插入 → 一次更新使版本号自增 → 用旧版本号再更新则冲突。
    @Test
    void updateBumpsVersionAndStaleUpdateFails() {
        inTransaction(entityManager -> operations.insert(new VersionedEntity(1L, "draft")));
        long versionAfterInsert = currentVersion(1L);

        inTransaction(entityManager -> operations.update(new VersionedEntity(1L, "published", versionAfterInsert)));
        long versionAfterUpdate = currentVersion(1L);

        assertThat(versionAfterUpdate).isGreaterThan(versionAfterInsert);
        assertThatThrownBy(() -> inTransaction(
                        entityManager -> operations.update(new VersionedEntity(1L, "stale", versionAfterInsert))))
                .isInstanceOf(OptimisticLockException.class);
    }

    /// 成功更新后，调用方手里的对象拿到新版本号（`@Version` 回写契约）。
    @Test
    void updateWritesBackNewVersionToCallerObject() {
        inTransaction(entityManager -> operations.insert(new VersionedEntity(2L, "draft")));
        VersionedEntity detached = new VersionedEntity(2L, "published", currentVersion(2L));

        inTransaction(entityManager -> operations.update(detached));

        assertThat(detached.version()).isEqualTo(currentVersion(2L));
    }

    private void inTransaction(Consumer<EntityManager> work) {
        harness.inTransaction(work);
        harness.entityManager().clear();
    }

    private long currentVersion(long id) {
        return harness.entityManager()
                .createQuery("select v.version from VersionedEntity v where v.id = :id", Long.class)
                .setParameter("id", id)
                .getSingleResult();
    }
}
