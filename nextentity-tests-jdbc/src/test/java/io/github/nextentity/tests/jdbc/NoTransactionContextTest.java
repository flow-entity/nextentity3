package io.github.nextentity.tests.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.testing.ItemStatus;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/// JDBC 后端的写入建立在自动提交之上（§7 决策 3）：没有显式事务上下文也照常成功。
/// 对应 tests-jpa 的 `ItemNoTransactionContextTest` 反过来断言：那边要求抛 `TransactionRequiredException`，
/// 这边语义不同，故重写而非移植（§7.1 末条，Lead 4/3）。
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

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
