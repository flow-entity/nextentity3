package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.LockMode;
import io.github.nextentity.testing.ItemStatus;
import io.github.nextentity.tests.jpa.support.MySqlItemSupport;
import io.github.nextentity.tests.jpa.support.MySqlL4Gate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 悲观锁在真实 MySQL 上的形态：事务内读到行，且语句必须带 `FOR UPDATE`。
///
/// 与 H2 侧 `ItemLockConsistencyTest` 同一场景、同一夹具、同一断言；本类只换方言。
/// 上真库的意义在 MySQL 侧：`FOR UPDATE` 是行锁语义的入口，H2 兼容模式接受这条语法，
/// 但只有真 MySQL 能证明它在事务内真的下发了。
@ExtendWith(MySqlL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlItemLockIntegrationTest {

    private final MySqlItemSupport support = new MySqlItemSupport();

    @BeforeEach
    void seed() {
        support.seedData(List.of(support.newItem(1L, "apple", 10, 10, null, ItemStatus.NEW)));
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }

    @Test
    void pessimisticWriteLockReadsRowAndEmitsForUpdate() {
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
        List<ItemEntity> rows = support.inTransaction(() -> support.query()
                .where(support.idRef())
                .eq(999L)
                .lock(LockMode.PESSIMISTIC_WRITE)
                .list());

        assertThat(rows).isEmpty();
    }
}
