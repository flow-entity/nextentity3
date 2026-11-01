package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.entity.Customer;
import io.github.nextentity.jpa.entity.OrderStatus;
import io.github.nextentity.jpa.entity.ShopOrder;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 关联属性作为**谓词末段**是合法形态：`where(关联).isNull()` / `eq(关联对象)` / `in(...)` 表达的是
/// 本表外键列的条件，谓词渲染走 `JpaPaths#path`，与「关联对象不能当**投影列**」是两件事。
///
/// 3.2.0 把 `rejectAssociationLeaf` 放进了共享的 `path()`（它是谓词、排序、分组、条件写 WHERE 的
/// 唯一路径解析点），于是一批基线可用的关联谓词在发 SQL 前抛 `UOE: 投影关联对象未支持`——文案自己
/// 说的就是「投影」，而调用点不是投影。本类把谓词 / 计数 / 存在性 / 条件写 WHERE 四个入口钉住，
/// 同时由 `JpaAssociationProjectionTest` 继续钉住「投影列上的关联对象仍拒绝」。
///
/// 语义约定（与 `EntityPath` javadoc 一致）：LEFT 语义，外键为 `null` 的行在 `isNull` 下命中、
/// 在 `eq`/`in` 下不命中；条件写 WHERE 用的是同一条外键条件。
class JpaAssociationPredicateTest extends JpaTestSupport {

    @Test
    @DisplayName("where(关联).isNull()：语义是外键列为 null，命中没有客户的订单")
    void associationIsNullMatchesNullForeignKey() {
        drainSql();

        List<Long> ids =
                inTx(entityManager -> orders(entityManager)
                        .query()
                        .where(ShopOrder::getCustomer)
                        .isNull()
                        .orderBy(ShopOrder::getId)
                        .asc()
                        .list()
                        .stream()
                        .map(ShopOrder::getId)
                        .toList());

        assertThat(ids).as("100 号有客户、101 号外键为空").containsExactly(101L);
        List<String> statements = normalized(drainSql());
        assertThat(statements).hasSize(1);
        assertThat(statements.getFirst())
                .as("关联谓词必须渲染成本表外键列的空值判断，而不是对被关联表做 join")
                .contains("from shop_order")
                .contains("customer_id is null");
    }

    @Test
    @DisplayName("where(关联).eq(关联对象)：按外键比较，命中该客户的行")
    void associationEqualsEntityComparesForeignKey() {
        List<Long> ids = inTx(entityManager -> orders(entityManager)
                .query()
                .where(ShopOrder::getCustomer)
                .eq(entityManager.getReference(Customer.class, 10L))
                .orderBy(ShopOrder::getId)
                .asc()
                .list()
                .stream()
                .map(ShopOrder::getId)
                .toList());

        assertThat(ids).as("100 号的客户是 10，101 号外键为空").containsExactly(100L);
    }

    @Test
    @DisplayName("where(关联).in(关联对象集合)：按外键做 IN")
    void associationInComparesForeignKey() {
        List<Long> ids = inTx(entityManager -> {
            Customer alice = entityManager.getReference(Customer.class, 10L);
            return orders(entityManager)
                    .query()
                    .where(ShopOrder::getCustomer)
                    .in(List.of(alice))
                    .orderBy(ShopOrder::getId)
                    .asc()
                    .list()
                    .stream()
                    .map(ShopOrder::getId)
                    .toList();
        });

        assertThat(ids).containsExactly(100L);
    }

    @Test
    @DisplayName("关联谓词上的 count() / exists()：与 list 同口径")
    void countAndExistsUseTheSameAssociationPredicatePath() {
        long missing = inTx(entityManager -> orders(entityManager)
                .query()
                .where(ShopOrder::getCustomer)
                .isNull()
                .count());
        boolean any = inTx(entityManager -> orders(entityManager)
                .query()
                .where(ShopOrder::getCustomer)
                .isNotNull()
                .exists());

        assertThat(missing).isEqualTo(1L);
        assertThat(any).isTrue();
    }

    @Test
    @DisplayName("条件写 WHERE 的关联谓词同样可用：只改外键为 null 的那一行")
    void conditionalUpdateWhereAcceptsAssociationPredicate() {
        int affected = inTx(entityManager -> orders(entityManager)
                .update()
                .set(ShopOrder::getStatus, OrderStatus.CREATED)
                .where(ShopOrder::getCustomer)
                .isNull()
                .execute());

        assertThat(affected).isEqualTo(1);
        inTx(entityManager -> {
            assertThat(entityManager.find(ShopOrder.class, 101L).getStatus())
                    .as("条件写命中的是外键为 null 的 101 号")
                    .isEqualTo(OrderStatus.CREATED);
            assertThat(entityManager.find(ShopOrder.class, 100L).getStatus())
                    .as("100 号有客户，不得被连带改掉")
                    .isEqualTo(OrderStatus.PAID);
            return null;
        });
    }

    @Test
    @DisplayName("对照：谓词可用不代表投影列可用——select(关联对象) 仍拒绝")
    void associationValueProjectionIsStillRejected() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> inTx(entityManager -> orders(entityManager)
                        .query()
                        .select(Path.of(ShopOrder::getCustomer))
                        .list())))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("投影关联对象未支持");
    }

    @Test
    @DisplayName("投影外延不外溢：聚合参数上的关联对象仍拒绝（select(count(关联))）")
    void aggregateOverAssociationIsStillRejected() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> inTx(entityManager -> orders(entityManager)
                        .query()
                        .select(Path.of(ShopOrder::getCustomer).count())
                        .list())))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("投影关联对象未支持");
    }

    @Test
    @DisplayName("投影外延不外溢：groupBy(关联对象) 仍拒绝（与 list / count 同口径）")
    void groupByAssociationIsStillRejected() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> inTx(entityManager -> orders(entityManager)
                        .query()
                        .groupBy(ShopOrder::getCustomer)
                        .list())))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("投影关联对象未支持");
    }

    private EntityOperations<ShopOrder> orders(EntityManager entityManager) {
        return new JpaEntityOperationsFactory(entityManager, entityManager.getEntityManagerFactory())
                .operations(ShopOrder.class);
    }
}
