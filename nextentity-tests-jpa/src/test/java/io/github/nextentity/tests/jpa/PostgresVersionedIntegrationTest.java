package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.tests.jpa.support.PostgresEntityOperationsSupport;
import io.github.nextentity.tests.jpa.support.PostgresL4Gate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// `@Version` 乐观锁在真实 PostgreSQL 上的形态：`update` 让版本号自增，过期版本号更新抛
/// `OptimisticLockException`。
///
/// 与 H2 侧 `ItemVersionConsistencyTest` 同一夹具、同一断言；本类只换方言。上真库的意义在于
/// 版本条件落在 SQL 的 `WHERE id=? AND version=?` 上，影响行数为 0 的判定由驱动与库共同给出。
@ExtendWith(PostgresL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresVersionedIntegrationTest {

    private final PostgresEntityOperationsSupport<VersionedEntity> support =
            new PostgresEntityOperationsSupport<>(VersionedEntity.class);

    @BeforeEach
    void reset() {
        support.truncate("VersionedEntity");
        support.clear();
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }

    /// 插入 → 一次更新使版本号自增 → 用旧版本号再更新则冲突。
    @Test
    void updateBumpsVersionAndStaleUpdateFails() {
        inTransaction(_ -> support.operations().insert(new VersionedEntity(1L, "draft")));
        long versionAfterInsert = currentVersion(1L);

        inTransaction(_ -> support.operations().update(new VersionedEntity(1L, "published", versionAfterInsert)));
        long versionAfterUpdate = currentVersion(1L);

        assertThat(versionAfterUpdate).isGreaterThan(versionAfterInsert);
        assertThatThrownBy(() -> inTransaction(
                        _ -> support.operations().update(new VersionedEntity(1L, "stale", versionAfterInsert))))
                .isInstanceOf(OptimisticLockException.class);
    }

    /// 成功更新后，调用方手里的对象拿到新版本号（`@Version` 回写契约）。
    @Test
    void updateWritesBackNewVersionToCallerObject() {
        inTransaction(_ -> support.operations().insert(new VersionedEntity(2L, "draft")));
        VersionedEntity detached = new VersionedEntity(2L, "published", currentVersion(2L));

        inTransaction(_ -> support.operations().update(detached));

        assertThat(detached.version()).isEqualTo(currentVersion(2L));
    }

    private void inTransaction(Consumer<EntityManager> work) {
        support.inTransaction(work);
        support.clear();
    }

    private long currentVersion(long id) {
        return support.entityManager()
                .createQuery("select v.version from VersionedEntity v where v.id = :id", Long.class)
                .setParameter("id", id)
                .getSingleResult();
    }
}
