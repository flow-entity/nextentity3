package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.JpaEntityOperationsFactory;
import io.github.nextentity.tests.jpa.support.HibernateHarness;
import jakarta.persistence.OptimisticLockException;
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

    /// 批量里**中间**一条撞乐观锁：逐条执行、遇冲突即抛，已处理的那几条留在调用方事务里。
    ///
    /// 本库不开事务（USAGE §11），所以「已处理的前几条」既没有单独提交、也没有被库回滚——
    /// 它们只是躺在调用方的事务里。实测钉住三件事：冲突条的 id 与**陈旧版本值**出现在消息里
    /// （否则「版本条件丢失」的实现也能过）；已处理条的 `@Version` 已回写内存、冲突条与后续条没被碰；
    /// 调用方回滚后三条全部还原（证明库没有偷偷提交前几条）。
    @Test
    void updateAllStopsAtFirstConflictKeepingEarlierWritesInCallerTransaction() {
        harness.inTransaction(_ -> operations.insertAll(List.of(
                new VersionedEntity(1L, "one"), new VersionedEntity(2L, "two"), new VersionedEntity(3L, "three"))));
        harness.entityManager().clear();

        List<VersionedEntity> batch = List.of(
                new VersionedEntity(1L, "one-updated", 0L),
                new VersionedEntity(2L, "two-stale", 7L),
                new VersionedEntity(3L, "three-updated", 0L));

        assertThatThrownBy(() -> harness.inTransaction(_ -> operations.updateAll(batch)))
                .isInstanceOf(OptimisticLockException.class)
                .hasMessageContaining("不存在: id=2")
                .hasMessageContaining("version=7");

        assertThat(batch)
                .describedAs("第 1 条已处理（版本号回写），冲突条与后续条未处理")
                .extracting(VersionedEntity::version)
                .containsExactly(1L, 7L, 0L);

        harness.entityManager().getTransaction().rollback();
        harness.entityManager().clear();

        assertThat(operations.query().orderBy(VersionedEntity::id).asc().list())
                .describedAs("调用方回滚 → 已处理的那条也一并消失，说明库没有自己提交")
                .extracting(VersionedEntity::name, VersionedEntity::version)
                .containsExactly(tuple("one", 0L), tuple("two", 0L), tuple("three", 0L));
    }

    /// 批量里最后一条是「行不存在」：抛错后调用方**提交**，前一条的更新才生效。
    ///
    /// 与上一条互为对照：同一次 `updateAll` 的部分写入，成败完全由调用方的事务决定——
    /// 这就是「本库不自己开事务」在写路径上的可观测形态。若哪天有人在 `updateAll` 里加了
    /// 「整批回滚」或「整批提交」，两条用例之一必红。
    @Test
    void updateAllPartialWritesBecomeVisibleOnlyWhenCallerCommits() {
        harness.inTransaction(_ -> operations.insertAll(List.of(new VersionedEntity(4L, "four"))));
        harness.entityManager().clear();

        List<VersionedEntity> batch =
                List.of(new VersionedEntity(4L, "four-updated", 0L), new VersionedEntity(5L, "absent", 0L));

        assertThatThrownBy(() -> harness.inTransaction(_ -> operations.updateAll(batch)))
                .isInstanceOf(OptimisticLockException.class)
                .hasMessageContaining("不存在: id=5");

        harness.entityManager().getTransaction().commit();
        harness.entityManager().clear();

        assertThat(operations.query().orderBy(VersionedEntity::id).asc().list())
                .describedAs("提交 → 已处理的那条留下新名字与新版本号，冲突条没有多出任何行")
                .extracting(VersionedEntity::id, VersionedEntity::name, VersionedEntity::version)
                .containsExactly(tuple(4L, "four-updated", 1L));
        assertThat(operations.query().count()).isEqualTo(1L);
    }

    private long currentVersion(long id) {
        return harness.entityManager()
                .createQuery("select v.version from VersionedEntity v where v.id = :id", Long.class)
                .setParameter("id", id)
                .getSingleResult();
    }
}
