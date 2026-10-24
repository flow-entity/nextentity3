package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.JpaEntityOperationsFactory;
import io.github.nextentity.testing.FixtureRow;
import io.github.nextentity.testing.Fixtures;
import io.github.nextentity.testing.ItemStatus;
import io.github.nextentity.tests.jpa.support.HibernateHarness;
import jakarta.persistence.TransactionRequiredException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// 事务边界由调用方掌握（USAGE §11「本库不自己开事务」）：本库既不替你 `begin`，也不劫持事务。
///
/// 之前所有落库用例都在 `harness.inTransaction(...)` 里跑，等于这句话零证据——把生产代码改成
/// 「自己开事务并提交」或「自己开事务并回滚」，套件照样全绿。这里刻意**不开**事务：
/// - 读：`count()` / `list()` 直接返回库里的行（读路径不需要事务，本库也不偷偷开一个）；
/// - 写：`update` 抛 `TransactionRequiredException`，因为 JPA 规定 `executeUpdate` 必须在事务内，
///   而本库没有替调用方开——这正是「边界由调用方决定」的反面证据：换成自开事务的实现，这条会红。
///
/// 夹具写入仍由测试自己包一层事务：不包就写不进去，本身就是同一件事的证据。
class ItemNoTransactionContextTest {

    private final HibernateHarness harness = HibernateHarness.start("item_no_transaction", ItemEntity.class);
    private final EntityOperations<ItemEntity> operations;

    ItemNoTransactionContextTest() {
        this.operations = new JpaEntityOperationsFactory(harness.entityManager(), harness.entityManagerFactory())
                .operations(ItemEntity.class);
    }

    @AfterEach
    void closeHarness() {
        harness.close();
    }

    /// 没有调用方事务时，查询照常工作并返回库里的行。
    @Test
    void readsWorkWithoutCallerTransaction() {
        seedInCallerTransaction();

        assertThat(operations.query().count()).isEqualTo(4L);
        assertThat(operations.query().orderBy(ItemEntity::id).asc().list())
                .extracting(ItemEntity::name)
                .containsExactly("apple", "banana", "cherry", "apple pie");
    }

    /// 没有调用方事务时，`update` 被 JPA 拒绝：本库没有替调用方开事务，行也不会被改。
    @Test
    void updateWithoutCallerTransactionIsRejected() {
        seedInCallerTransaction();

        assertThatThrownBy(
                        () -> operations.update(new ItemEntity(2L, "banana-updated", 20, 10, "n1", ItemStatus.ACTIVE)))
                .isInstanceOf(TransactionRequiredException.class);

        harness.entityManager().clear();
        assertThat(operations.query().orderBy(ItemEntity::id).asc().list())
                .extracting(ItemEntity::name)
                .containsExactly("apple", "banana", "cherry", "apple pie");
    }

    /// 夹具写入必须由调用方包事务（本库不替测试开）：不开就写不进去，读回来仍是空表。
    @Test
    void writesWithoutCallerTransactionAreNotPersisted() {
        operations.insertAll(Fixtures.ITEM_ROWS.stream()
                .map(ItemNoTransactionContextTest::of)
                .toList());
        harness.entityManager().clear();

        assertThat(operations.query().count()).isZero();
    }

    private void seedInCallerTransaction() {
        harness.inTransaction(_ -> operations.insertAll(Fixtures.ITEM_ROWS.stream()
                .map(ItemNoTransactionContextTest::of)
                .toList()));
        harness.entityManager().clear();
    }

    private static ItemEntity of(FixtureRow row) {
        return new ItemEntity(row.id(), row.name(), row.score(), row.score2(), row.note(), row.status());
    }
}
