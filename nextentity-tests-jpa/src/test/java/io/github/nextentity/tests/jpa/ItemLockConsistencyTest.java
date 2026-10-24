package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.LockMode;
import io.github.nextentity.testing.ItemStatus;
import java.util.ArrayList;
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
        support.seedData(List.of(support.newItem(1L, "apple", 10, 10, null, ItemStatus.NEW)));
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
        support.seedData(List.of(support.newItem(1L, "apple", 10, 10, null, ItemStatus.NEW)));

        List<ItemEntity> rows = support.inTransaction(() -> support.query()
                .where(support.idRef())
                .eq(999L)
                .lock(LockMode.PESSIMISTIC_WRITE)
                .list());

        assertThat(rows).isEmpty();
    }

    /// 五种 `LockMode` 都要走一遍：后端要么按同名映射成 `LockModeType` 放行，要么当场拒绝。
    ///
    /// 生产代码是 `LockModeType.valueOf(spec.lock().name())`——名字映射，`api.LockMode` 与
    /// jakarta 侧任何一侧改名，这里就必须红；只在 `PESSIMISTIC_WRITE` 一条上打转是钉不住的。
    ///
    /// 实测语义（Hibernate 6 + H2，`ItemEntity` 无 `@Version`）：三种悲观锁里
    /// `READ` / `WRITE` 渲染 `for update`，`FORCE_INCREMENT` 与两种乐观锁要求版本字段，
    /// 被 Hibernate 当场拒绝。拒绝也是契约的一部分，所以逐条钉住，不写成「跳过」。
    @Test
    void everyLockModeIsEitherHonouredOrRejected() {
        List<String> outcomes = new ArrayList<>();
        for (LockMode mode : LockMode.values()) {
            outcomes.add(outcomeOf(mode));
        }

        assertThat(outcomes)
                .containsExactly(
                        "OPTIMISTIC rejected HibernateException"
                                + " [OPTIMISTIC] not supported for non-versioned entities",
                        "OPTIMISTIC_FORCE_INCREMENT rejected HibernateException"
                                + " [OPTIMISTIC_FORCE_INCREMENT] not supported for non-versioned entities",
                        "PESSIMISTIC_READ rows=1 forUpdate=true",
                        "PESSIMISTIC_WRITE rows=1 forUpdate=true",
                        "PESSIMISTIC_FORCE_INCREMENT rejected HibernateException"
                                + " [PESSIMISTIC_FORCE_INCREMENT] not supported for non-versioned entities");
    }

    /// 跑一次加锁查询，把结果压成一行可比的文本：放行则记行数与是否渲染 `for update`，拒绝则记异常与原因。
    ///
    /// 每种模式单开一个 `JpaItemSupport`：被 Hibernate 拒绝的模式会污染 EntityManager，
    /// 复用同一个会把后续模式一律变成 `IllegalStateException`（假红）。
    private String outcomeOf(LockMode mode) {
        try (JpaItemSupport fresh =
                new JpaItemSupport("item_lock_" + mode.name().toLowerCase())) {
            fresh.seedData(List.of(fresh.newItem(1L, "apple", 10, 10, null, ItemStatus.NEW)));
            fresh.clearSql();
            try {
                List<ItemEntity> rows = fresh.inTransaction(() ->
                        fresh.query().where(fresh.idRef()).eq(1L).lock(mode).list());
                boolean forUpdate = fresh.drainSql().stream()
                        .anyMatch(sql -> sql.toLowerCase().contains("for update"));
                return mode + " rows=" + rows.size() + " forUpdate=" + forUpdate;
            } catch (RuntimeException e) {
                return mode + " rejected " + e.getClass().getSimpleName() + " " + withoutEntitySuffix(e);
            }
        }
    }

    /// 去掉 Hibernate 消息尾部的 `[实体全名]`，只留语义——换测试实体不该让这条用例红。
    private static String withoutEntitySuffix(RuntimeException e) {
        String message = String.valueOf(e.getMessage());
        return message.replaceAll("\\s*\\[[\\w.$]+]$", "").trim();
    }
}
