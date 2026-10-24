package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.tests.jpa.support.MySqlEntityOperationsSupport;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.junit.jupiter.Testcontainers;

/// 批量写在真实 MySQL 上的形态：`insertAll` 全量落库、`updateAll` 让每条记录的 `@Version` 自增
/// 并**回写调用方对象**。
///
/// 与 H2 侧 `ItemBatchWriteConsistencyTest` 同一夹具、同一断言；本类只换方言。
@Testcontainers(disabledWithoutDocker = true)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlBatchWriteIntegrationTest {

    private final MySqlEntityOperationsSupport<VersionedEntity> support =
            new MySqlEntityOperationsSupport<>(VersionedEntity.class);

    @BeforeEach
    void reset() {
        support.truncate("VersionedEntity");
        support.clear();
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }

    /// `insertAll` 一次写入多条，全部可读回。
    @Test
    void insertAllPersistsEveryEntity() {
        support.inTransaction(_ -> support.operations()
                .insertAll(List.of(
                        new VersionedEntity(1L, "one"),
                        new VersionedEntity(2L, "two"),
                        new VersionedEntity(3L, "three"))));
        support.clear();

        List<VersionedEntity> rows =
                support.operations().query().orderBy(VersionedEntity::id).asc().list();

        assertThat(rows).extracting(VersionedEntity::id).containsExactly(1L, 2L, 3L);
        assertThat(rows).extracting(VersionedEntity::name).containsExactly("one", "two", "three");
    }

    /// `updateAll` 让每条记录版本号自增，并把新版本号写回调用方手里的对象。
    @Test
    void updateAllBumpsVersionsAndWritesBackToCallerObjects() {
        support.inTransaction(_ -> support.operations()
                .insertAll(List.of(new VersionedEntity(1L, "one"), new VersionedEntity(2L, "two"))));
        support.clear();
        long versionBefore = currentVersion(1L);

        List<VersionedEntity> detached = List.of(
                new VersionedEntity(1L, "one-updated", versionBefore),
                new VersionedEntity(2L, "two-updated", versionBefore));

        support.inTransaction(_ -> support.operations().updateAll(detached));
        support.clear();

        assertThat(detached).extracting(VersionedEntity::version).containsOnly(versionBefore + 1);
        assertThat(currentVersion(1L)).isEqualTo(versionBefore + 1);
        assertThat(currentVersion(2L)).isEqualTo(versionBefore + 1);
        assertThat(support.operations()
                        .query()
                        .orderBy(VersionedEntity::id)
                        .asc()
                        .list())
                .extracting(VersionedEntity::name)
                .containsExactly("one-updated", "two-updated");
    }

    private long currentVersion(long id) {
        return support.entityManager()
                .createQuery("select v.version from VersionedEntity v where v.id = :id", Long.class)
                .setParameter("id", id)
                .getSingleResult();
    }
}
