package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.LockMode;
import io.github.nextentity.testing.ItemStatus;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// 悲观锁：`lock(PESSIMISTIC_WRITE)` 在事务内读到行，且语句必须带 `FOR UPDATE`。
///
/// 这条只放在 `nextentity-tests-jpa`、不进 `nextentity-testing` 的共享基类：断言要看真实下发的
/// SQL（`for update`），并且查询必须在事务里跑——SQL 抓取与事务装置只存在于 jpa 侧；一致性基类
/// 按 DESIGN §5 只做结果断言、不带后端依赖（`TESTING_STAYS_BACKEND_FREE` 会拒绝）。
///
/// 真实调用点：point-mall `LogisticsOrderRepository.findByOrderIdForUpdate`（`eq + lock + single`）。
class ItemLockConsistencyTest {

    private final JpaItemSupport support = new JpaItemSupport("item_lock");

    @AfterEach
    void closeSupport() {
        support.close();
    }

    @Test
    void pessimisticWriteLockReadsRowAndEmitsForUpdate() {
        support.seedData(List.of(support.newItem(1L, "apple", 10, null, ItemStatus.NEW)));
        support.clearSql();

        List<ItemEntity> rows = support.inTransaction(() -> support.query()
                .where(support.idRef())
                .eq(1L)
                .lock(LockMode.PESSIMISTIC_WRITE)
                .list());

        assertThat(rows).extracting(ItemEntity::id).containsExactly(1L);
        assertThat(support.drainSql()).anySatisfy(sql -> assertThat(sql).containsIgnoringCase("for update"));
    }

    @Test
    void pessimisticWriteLockOnMissingRowReturnsEmpty() {
        support.seedData(List.of(support.newItem(1L, "apple", 10, null, ItemStatus.NEW)));

        List<ItemEntity> rows = support.inTransaction(() -> support.query()
                .where(support.idRef())
                .eq(999L)
                .lock(LockMode.PESSIMISTIC_WRITE)
                .list());

        assertThat(rows).isEmpty();
    }
}
