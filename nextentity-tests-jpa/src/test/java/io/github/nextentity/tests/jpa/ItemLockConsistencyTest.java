package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.LockMode;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// 悲观锁：`lock(PESSIMISTIC_WRITE)` 在事务内读到行，且语句必须带 `FOR UPDATE`。
///
/// 这条只放在 `nextentity-tests-jpa`、不进 `nextentity-testing` 的共享基类：`api.LockStep` 上
/// `jakarta.persistence.LockModeType` 的重载会让调用方需要 jakarta 在 classpath 上，而一致性基类
/// 所在模块按 DESIGN §5 禁止后端依赖（`TESTING_STAYS_BACKEND_FREE` 会拒绝）。
///
/// 真实调用点：point-mall `LogisticsOrderRepository.findByOrderIdForUpdate`（`eq + lock + single`）。
class ItemLockConsistencyTest {

    private final JpaItemSupport support = new JpaItemSupport("item_lock");

    @AfterEach
    void closeSupport() {
        support.close();
    }

    /// 锁命中一行，语句含 `for update`。
    @Test
    void pessimisticWriteLockReadsRowAndEmitsForUpdate() {
        support.seedData(List.of(support.newItem(1L, "apple", 10, null)));
        support.clearSql();

        List<ItemEntity> rows = support.inTransaction(() -> support.query()
                .where(support.idRef())
                .eq(1L)
                .lock(LockMode.PESSIMISTIC_WRITE)
                .list());

        assertThat(rows).extracting(ItemEntity::id).containsExactly(1L);
        assertThat(support.drainSql()).anySatisfy(sql -> assertThat(sql).containsIgnoringCase("for update"));
    }

    /// 锁无命中时返回空，不抛异常。
    @Test
    void pessimisticWriteLockOnMissingRowReturnsEmpty() {
        support.seedData(List.of(support.newItem(1L, "apple", 10, null)));

        List<ItemEntity> rows = support.inTransaction(() -> support.query()
                .where(support.idRef())
                .eq(999L)
                .lock(LockMode.PESSIMISTIC_WRITE)
                .list());

        assertThat(rows).isEmpty();
    }
}
