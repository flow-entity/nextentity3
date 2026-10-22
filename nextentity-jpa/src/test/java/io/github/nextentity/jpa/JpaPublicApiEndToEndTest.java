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

    private static EntityOperations<ShopOrder> operations(EntityManager entityManager) {
        return new JpaEntityOperationsFactory(entityManager, entityManager.getEntityManagerFactory())
                .operations(ShopOrder.class);
    }
}
