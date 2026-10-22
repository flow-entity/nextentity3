package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.LockMode;
import io.github.nextentity.api.model.Slice;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.entity.OrderStatus;
import io.github.nextentity.jpa.entity.ShopOrder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/// 穿过公开 API 的端到端：`EntityOperationsFactory` → `EntityQuery`（core.builder）→
/// JpaCompiler → JpaExecutor → H2。
///
/// 与 {@link JpaEndToEndTest} 的分工：那个直接打 `JpaCompiler`/`JpaExecutor`（组件级），
/// 这个只走 `api` 的链式入口（装配级），证明 builder 与 spec 解释器真的接上了。
class JpaPublicApiEndToEndTest extends JpaTestSupport {

    @Test
    void listThroughQueryBuilder() {
        List<Long> ids = inTx(entityManager -> operations(entityManager)
                .query()
                .where(ShopOrder::getStatus)
                .eq(OrderStatus.PAID)
                .orderBy(ShopOrder::getId)
                .desc()
                .<ShopOrder>list()
                .stream()
                .map(ShopOrder::getId)
                .toList());
        assertThat(ids).containsExactly(100L);
    }

    @Test
    void sliceCarriesTotalAndMapsToConsumerType() {
        Slice<ShopOrder> slice = inTx(entityManager -> operations(entityManager)
                .query()
                .where(ShopOrder::getStatus)
                .in(List.of(OrderStatus.PAID, OrderStatus.CREATED))
                .orderBy(ShopOrder::getId)
                .asc()
                .slice(0, 10));
        assertThat(slice.total()).isEqualTo(2L);
        assertThat(slice.data()).extracting(ShopOrder::getId).containsExactly(100L, 101L);
        assertThat(slice.<String>to(page -> page.total() + ":" + page.data().size()))
                .isEqualTo("2:2");
    }

    @Test
    void terminalsAndLock() {
        inTx(entityManager -> {
            EntityOperations<ShopOrder> operations = operations(entityManager);
            assertThat(operations.query().count()).isEqualTo(2L);
            assertThat(operations
                            .query()
                            .where(ShopOrder::getStatus)
                            .eq(OrderStatus.PAID)
                            .exists())
                    .isTrue();
            assertThat(operations
                            .query()
                            .where(ShopOrder::getId)
                            .eq(100L)
                            .single()
                            .getId())
                    .isEqualTo(100L);
            // 自研枚举 + 2.2.3 兼容重载（point-mall 的 3 处调用点用的是后者）
            assertThat(operations
                            .query()
                            .where(ShopOrder::getId)
                            .eq(100L)
                            .lock(LockMode.PESSIMISTIC_WRITE)
                            .single()
                            .getId())
                    .isEqualTo(100L);
            assertThat(operations
                            .query()
                            .where(ShopOrder::getId)
                            .eq(101L)
                            .lock(LockModeType.PESSIMISTIC_WRITE)
                            .single()
                            .getId())
                    .isEqualTo(101L);
            return null;
        });
    }

    @Test
    void insertThenUpdateThroughOperations() {
        inTx(entityManager -> {
            EntityOperations<ShopOrder> operations = operations(entityManager);
            operations.insert(new ShopOrder(102L, null, OrderStatus.CREATED, new BigDecimal("7.00"), null));
            ShopOrder inserted =
                    operations.query().where(ShopOrder::getId).eq(102L).single();
            assertThat(inserted.getAmount()).isEqualByComparingTo("7.00");

            entityManager.detach(inserted);
            inserted.setAmount(new BigDecimal("8.00"));
            operations.update(inserted);
            assertThat(inserted.getVersion()).isEqualTo(1);
            return null;
        });
        inTx(entityManager -> {
            ShopOrder reloaded = operations(entityManager)
                    .query()
                    .where(ShopOrder::getId)
                    .eq(102L)
                    .single();
            assertThat(reloaded.getAmount()).isEqualByComparingTo("8.00");
            assertThat(reloaded.getVersion()).isEqualTo(1);
            return null;
        });
    }

    /// `selectDistinct` 的两个真实调用点（point-mall `DigitalCardRepository:85`、
    /// `OrderImportRecordRepository:71`）都是「单列投影 + `.list()`」形态。
    @Test
    void selectDistinctSingleColumn() {
        inTx(entityManager -> {
            EntityOperations<ShopOrder> operations = operations(entityManager);
            operations.insert(new ShopOrder(103L, null, OrderStatus.PAID, new BigDecimal("9.00"), null));

            List<OrderStatus> all =
                    operations.query().select(ShopOrder::getStatus).list();
            assertThat(all).containsExactlyInAnyOrder(OrderStatus.PAID, OrderStatus.CREATED, OrderStatus.PAID);

            List<OrderStatus> distinct =
                    operations.query().selectDistinct(ShopOrder::getStatus).list();
            assertThat(distinct).containsExactlyInAnyOrder(OrderStatus.PAID, OrderStatus.CREATED);
            return null;
        });
    }

    /// `insertAll` / `updateAll` 的真实调用点：point-mall `AppRepositoryImpl:103/119`。
    ///
    /// **事务边界是这条用例的前提，不是凑绿**：`updateAll` 走的是 `CriteriaUpdate`（语句级写，
    /// 绕过持久化上下文）。若在同一事务里紧跟一个尚未 flush 的 `persist`，库里还没有那行，
    /// `WHERE id=? AND version=?` 会 0 行命中 → 抛 `OptimisticLockException`。
    /// 2.2.3 的 `JpaPersistExecutor` 也是同一行为（同样不做 flush）。真实调用点分处两个事务
    /// （`insertAll` 与 `updateAll` 不在同一业务方法里），因此这里按「先提交插入（commit 会 flush），
    /// 再另起事务更新」的形态断言：批量更新在真实事务边界下确定性成功。
    @Test
    void insertAllThenUpdateAll() {
        inTx(entityManager -> {
            operations(entityManager)
                    .insertAll(List.of(
                            new ShopOrder(104L, null, OrderStatus.CREATED, new BigDecimal("1.00"), null),
                            new ShopOrder(105L, null, OrderStatus.CREATED, new BigDecimal("2.00"), null)));
            return null;
        });
        inTx(entityManager -> {
            EntityOperations<ShopOrder> operations = operations(entityManager);
            ShopOrder first =
                    operations.query().where(ShopOrder::getId).eq(104L).single();
            ShopOrder second =
                    operations.query().where(ShopOrder::getId).eq(105L).single();
            entityManager.detach(first);
            entityManager.detach(second);
            first.setAmount(new BigDecimal("11.00"));
            second.setAmount(new BigDecimal("22.00"));
            operations.updateAll(List.of(first, second));
            assertThat(first.getVersion()).isEqualTo(1);
            assertThat(second.getVersion()).isEqualTo(1);
            return null;
        });
        inTx(entityManager -> {
            EntityOperations<ShopOrder> operations = operations(entityManager);
            assertThat(operations
                            .query()
                            .where(ShopOrder::getId)
                            .eq(104L)
                            .single()
                            .getAmount())
                    .isEqualByComparingTo("11.00");
            assertThat(operations
                            .query()
                            .where(ShopOrder::getId)
                            .eq(105L)
                            .single()
                            .getAmount())
                    .isEqualByComparingTo("22.00");
            return null;
        });
    }

    private EntityOperations<ShopOrder> operations(EntityManager entityManager) {
        return new JpaEntityOperationsFactory(entityManager, entityManager.getEntityManagerFactory())
                .operations(ShopOrder.class);
    }
}
