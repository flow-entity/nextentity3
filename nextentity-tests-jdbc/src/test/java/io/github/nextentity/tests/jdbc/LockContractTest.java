package io.github.nextentity.tests.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import io.github.nextentity.api.LockMode;
import io.github.nextentity.testing.ItemStatus;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/// 锁契约（§4.3 #18/#19）：`PESSIMISTIC_WRITE` → `for update`、`PESSIMISTIC_READ` → H2 无 `FOR SHARE`
/// 时退化为 `for update`（更强锁，非等价，见 `H2Dialect#lock`）；其余三种在编译期拒绝；
/// 锁只接受实体查询，值选择上的锁同样拒绝。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LockContractTest {

    private final JdbcItemSupport support = new JdbcItemSupport("lock_contract_test");

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
        // 超时断言：读锁若被渲染成会挂起的形态（或方言兜底写锁死锁），这里会失败而不是把测试拖住。
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
