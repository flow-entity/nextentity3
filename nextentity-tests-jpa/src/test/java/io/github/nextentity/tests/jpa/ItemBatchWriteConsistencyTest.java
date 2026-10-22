package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.JpaEntityOperationsFactory;
import io.github.nextentity.tests.jpa.support.HibernateHarness;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// 批量写入一致性：`insertAll` 全量落库、`updateAll` 让每个实体的 `@Version` 自增并**回写调用方对象**。
///
/// 真实调用点：`BatchTransferService:160`、`OrderImportTaskService:445/728`（`insertAll`）、
/// `OrgProductService:1038/1122`（`updateAll`）；point-mall 的 `AppRepositoryImpl.updateAll`
/// 在 B 方案里要保证「批量更新后调用方列表拿到新版本号」，否则后续更新会撞乐观锁。
class ItemBatchWriteConsistencyTest {

    private final HibernateHarness harness = HibernateHarness.start("item_batch_write", VersionedEntity.class);
    private final EntityOperations<VersionedEntity> operations;

    ItemBatchWriteConsistencyTest() {
        this.operations = new JpaEntityOperationsFactory(harness.entityManager(), harness.entityManagerFactory())
                .operations(VersionedEntity.class);
    }

    @AfterEach
    void closeHarness() {
        harness.close();
    }

    /// `insertAll` 一次写入多条，全部可读回。
    @Test
    void insertAllPersistsEveryEntity() {
        harness.inTransaction(_ -> operations.insertAll(List.of(
                new VersionedEntity(1L, "one"), new VersionedEntity(2L, "two"), new VersionedEntity(3L, "three"))));
        harness.entityManager().clear();

        List<VersionedEntity> rows =
                operations.query().orderBy(VersionedEntity::id).asc().list();

        assertThat(rows).extracting(VersionedEntity::id).containsExactly(1L, 2L, 3L);
        assertThat(rows).extracting(VersionedEntity::name).containsExactly("one", "two", "three");
    }

    /// `updateAll` 让每条记录版本号自增，并把新版本号写回调用方手里的对象。
    @Test
    void updateAllBumpsVersionsAndWritesBackToCallerObjects() {
        harness.inTransaction(
                _ -> operations.insertAll(List.of(new VersionedEntity(1L, "one"), new VersionedEntity(2L, "two"))));
        harness.entityManager().clear();
        long versionBefore = currentVersion(1L);

        List<VersionedEntity> detached = List.of(
                new VersionedEntity(1L, "one-updated", versionBefore),
                new VersionedEntity(2L, "two-updated", versionBefore));

        harness.inTransaction(_ -> operations.updateAll(detached));
        harness.entityManager().clear();

        assertThat(detached).extracting(VersionedEntity::version).containsOnly(versionBefore + 1);
        assertThat(currentVersion(1L)).isEqualTo(versionBefore + 1);
        assertThat(currentVersion(2L)).isEqualTo(versionBefore + 1);
        assertThat(operations.query().orderBy(VersionedEntity::id).asc().list())
                .extracting(VersionedEntity::name)
                .containsExactly("one-updated", "two-updated");
    }

    private long currentVersion(long id) {
        return harness.entityManager()
                .createQuery("select v.version from VersionedEntity v where v.id = :id", Long.class)
                .setParameter("id", id)
                .getSingleResult();
    }
}
