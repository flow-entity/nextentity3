package io.github.nextentity.tests.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import io.github.nextentity.api.LockMode;
import io.github.nextentity.testing.ItemStatus;
import io.github.nextentity.tests.jdbc.support.MySqlItemSupport;
import io.github.nextentity.tests.jdbc.support.MySqlL4Gate;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// `LockContractTest` 的 MySQL 通道：锁语义是方言相关能力，两方言各钉一次（见 USAGE §13.4.5）。
@ExtendWith(MySqlL4Gate.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlLockContractIntegrationTest {

    private final MySqlItemSupport support = new MySqlItemSupport();

    @BeforeEach
    void seed() {
        support.seedData(List.of(item(1L, "a", 10), item(2L, "b", 20), item(3L, "c", 30)));
    }

    @Test
    void pessimisticWriteRunsInsideTransactionAndReturnsRows() {
        List<ItemEntity> rows = support.inTransaction(() -> support.query()
                .orderBy(support.idRef())
                .asc()
                .lock(LockMode.PESSIMISTIC_WRITE)
                .list());
        assertThat(rows).extracting(ItemEntity::id).containsExactly(1L, 2L, 3L);
    }

    @Test
    void pessimisticReadRunsInsideTransactionAndReturnsRows() {
        // `for share` 下取行必须能返回（不挂起）；超时断言区分「能跑」与「锁住自己」。
        assertTimeout(Duration.ofSeconds(10), () -> {
            List<ItemEntity> rows = support.inTransaction(() -> support.query()
                    .orderBy(support.idRef())
                    .asc()
                    .lock(LockMode.PESSIMISTIC_READ)
                    .list());
            assertThat(rows).extracting(ItemEntity::id).containsExactly(1L, 2L, 3L);
        });
    }

    @Test
    void otherLockModesAreRejected() {
        for (LockMode mode : List.of(
                LockMode.OPTIMISTIC, LockMode.OPTIMISTIC_FORCE_INCREMENT, LockMode.PESSIMISTIC_FORCE_INCREMENT)) {
            assertThatThrownBy(() -> support.query().lock(mode).list())
                    .as("锁模式 %s 必须拒绝", mode)
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("JDBC 后端只支持 PESSIMISTIC_WRITE / PESSIMISTIC_READ: " + mode);
        }
    }

    @Test
    void lockOnValueSelectionIsRejected() {
        assertThatThrownBy(() -> support.query()
                        .select(support.nameRef())
                        .lock(LockMode.PESSIMISTIC_WRITE)
                        .list())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("锁只支持实体查询: " + ItemEntity.class.getName());
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }

    private static ItemEntity item(long id, String name, Integer score) {
        return new ItemEntity(id, name, score, score, null, ItemStatus.ACTIVE);
    }
}
