package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.testing.FixtureRow;
import io.github.nextentity.testing.Fixtures;
import io.github.nextentity.testing.ItemStatus;
import io.github.nextentity.tests.jpa.support.MySqlEntityOperationsSupport;
import io.github.nextentity.tests.jpa.support.MySqlL4Gate;
import jakarta.persistence.TransactionRequiredException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 「本库不自己开事务」在真 MySQL 上的同一形态：读不需要事务，写必须由调用方开事务。
///
/// 与 H2 侧 `ItemNoTransactionContextTest` 同一夹具、同一断言，本类只换方言——所以两方言若在
/// 「无事务时的读写行为」上分家（例如某个驱动默认自动提交、把 `executeUpdate` 放过），这一条会先红。
@ExtendWith(MySqlL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlItemNoTransactionIntegrationTest {

    private final MySqlEntityOperationsSupport<ItemEntity> support =
            new MySqlEntityOperationsSupport<>(ItemEntity.class);

    @BeforeEach
    void reset() {
        support.truncate("ItemEntity");
        support.clear();
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }

    /// 没有调用方事务时，查询照常工作并返回库里的行。
    @Test
    void readsWorkWithoutCallerTransaction() {
        seedInCallerTransaction();

        assertThat(support.operations().query().count()).isEqualTo(4L);
        assertThat(support.operations().query().orderBy(ItemEntity::id).asc().list())
                .extracting(ItemEntity::name)
                .containsExactly("apple", "banana", "cherry", "apple pie");
    }

    /// 没有调用方事务时，`update` 被 JPA 拒绝，且行没被改。
    @Test
    void updateWithoutCallerTransactionIsRejected() {
        seedInCallerTransaction();

        assertThatThrownBy(() -> support.operations()
                        .update(new ItemEntity(2L, "banana-updated", 20, 10, "n1", ItemStatus.ACTIVE)))
                .isInstanceOf(TransactionRequiredException.class);

        support.clear();
        assertThat(support.operations().query().orderBy(ItemEntity::id).asc().list())
                .extracting(ItemEntity::name)
                .containsExactly("apple", "banana", "cherry", "apple pie");
    }

    private void seedInCallerTransaction() {
        support.inTransaction(_ -> support.operations()
                .insertAll(Fixtures.ITEM_ROWS.stream()
                        .map(MySqlItemNoTransactionIntegrationTest::of)
                        .toList()));
        support.clear();
    }

    private static ItemEntity of(FixtureRow row) {
        return new ItemEntity(row.id(), row.name(), row.score(), row.score2(), row.note(), row.status());
    }
}
