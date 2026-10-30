package io.github.nextentity.tests.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.testing.ItemStatus;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/// JDBC 后端的写入建立在自动提交之上（DESIGN §7.2 的决策口径：事务归调用方）：没有显式事务上下文也照常成功。
/// 对应 tests-jpa 的 `ItemNoTransactionContextTest` 反过来断言：那边要求抛 `TransactionRequiredException`，
/// 这边语义不同，故重写而非移植（见 DESIGN §7.1 的 CUT 台账）。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NoTransactionContextTest {

    private final JdbcItemSupport support = new JdbcItemSupport("no_transaction_context_test");

    @Test
    void updateSucceedsWithoutExplicitTransaction() {
        support.seedData(List.of(new ItemEntity(1L, "before", 10, 10, null, ItemStatus.ACTIVE)));

        support.operations().update(new ItemEntity(1L, "after", 10, 10, null, ItemStatus.ACTIVE));

        assertThat(support.query().where(Path.of(ItemEntity::id).eq(1L)).first().name())
                .isEqualTo("after");
    }

    /// `insertAll` 同样没有"不开事务就不落库"这回事：JDBC 的自动提交把每一行都提交掉。
    ///
    /// 与 tests-jpa 的 `ItemNoTransactionContextTest#writesWithoutCallerTransactionAreNotPersisted`
    /// **行为相反**，故重写而非移植：JPA 的 `executeUpdate` 规定必须在事务内，本库不替调用方开，
    /// 所以那边读回是空表；JDBC 走 `Connection` 的自动提交，不落库才是 bug。
    /// oracle 是真库读回的条数与内容——只断言"没抛异常"会把「静默丢行」也判成绿。
    @Test
    void insertAllSucceedsWithoutExplicitTransaction() {
        List<ItemEntity> rows = List.of(
                new ItemEntity(21L, "kilo", 21, 21, "n21", ItemStatus.NEW),
                new ItemEntity(22L, "lima", 22, 22, null, ItemStatus.DONE));

        support.operations().insertAll(rows);

        assertThat(support.query().where(Path.of(ItemEntity::id).in(21L, 22L)).count())
                .as("无显式事务时 insertAll 也必须真的落库（自动提交语义）")
                .isEqualTo(2L);
        assertThat(support.query()
                        .where(Path.of(ItemEntity::id).in(21L, 22L))
                        .orderBy(ItemEntity::id)
                        .asc()
                        .list())
                .extracting(ItemEntity::name)
                .as("落库内容必须是写进去的那两行，而不是空表或串行")
                .containsExactly("kilo", "lima");
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
