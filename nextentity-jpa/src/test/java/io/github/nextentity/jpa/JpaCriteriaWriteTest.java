package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.entity.Customer;
import io.github.nextentity.jpa.entity.ShopOrder;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 条件 update / delete 的执行路径（`CriteriaUpdate` / `CriteriaDelete`）：
/// 返回值就是受影响行数，写的是列值本身而不是"先读出来再逐行改"。
class JpaCriteriaWriteTest extends JpaTestSupport {

    @Test
    @DisplayName("条件 update：返回受影响行数，且只改匹配行")
    void conditionalUpdateReturnsAffectedRows() {
        int affected = inTx(entityManager -> orders(entityManager)
                .update()
                .set(Path.of(ShopOrder::getAmount), new BigDecimal("42.00"))
                .where(ShopOrder::getId)
                .eq(100L)
                .execute());

        assertThat(affected).isEqualTo(1);
        inTx(entityManager -> {
            assertThat(entityManager.find(ShopOrder.class, 100L).getAmount()).isEqualByComparingTo("42.00");
            assertThat(entityManager.find(ShopOrder.class, 101L).getAmount()).isEqualByComparingTo("5.00");
            return null;
        });
    }

    @Test
    @DisplayName("不带 where 的 update 作用于全表")
    void updateWithoutWhereTouchesWholeTable() {
        int affected = inTx(entityManager -> orders(entityManager)
                .update()
                .set(Path.of(ShopOrder::getAmount), new BigDecimal("0.01"))
                .execute());

        assertThat(affected).isEqualTo(2);
        inTx(entityManager -> {
            assertThat(entityManager.find(ShopOrder.class, 100L).getAmount()).isEqualByComparingTo("0.01");
            assertThat(entityManager.find(ShopOrder.class, 101L).getAmount()).isEqualByComparingTo("0.01");
            return null;
        });
    }

    @Test
    @DisplayName("条件 update 可以改关联外键（值来自关联对象的 @Id）")
    void conditionalUpdateWritesAssociationForeignKey() {
        int affected = inTx(entityManager -> orders(entityManager)
                .update()
                .set(Path.of(ShopOrder::getCustomer), new Customer(11L, 2L, "Bob", null))
                .where(ShopOrder::getId)
                .eq(100L)
                .execute());

        assertThat(affected).isEqualTo(1);
        inTx(entityManager -> {
            assertThat(entityManager.find(ShopOrder.class, 100L).getCustomer().getId())
                    .isEqualTo(11L);
            assertThat(entityManager.find(ShopOrder.class, 101L).getCustomer()).isNull();
            return null;
        });
    }

    @Test
    @DisplayName("条件 update 可以把关联置空（写 NULL 而不是跳过列）")
    void conditionalUpdateCanNullAssociation() {
        int affected = inTx(entityManager -> orders(entityManager)
                .update()
                .set(Path.of(ShopOrder::getCustomer), null)
                .where(ShopOrder::getId)
                .eq(100L)
                .execute());

        assertThat(affected).isEqualTo(1);
        inTx(entityManager -> {
            assertThat(entityManager.find(ShopOrder.class, 100L).getCustomer()).isNull();
            return null;
        });
    }

    @Test
    @DisplayName("条件 delete：返回删除行数，行真的消失")
    void conditionalDeleteRemovesMatchingRows() {
        int affected = inTx(entityManager ->
                orders(entityManager).delete().where(ShopOrder::getId).eq(101L).execute());

        assertThat(affected).isEqualTo(1);
        inTx(entityManager -> {
            assertThat(entityManager.find(ShopOrder.class, 101L)).isNull();
            assertThat(entityManager.find(ShopOrder.class, 100L)).isNotNull();
            return null;
        });
    }

    @Test
    @DisplayName("不带 where 的 delete 清空全表")
    void deleteWithoutWhereEmptiesTable() {
        int affected = inTx(entityManager -> orders(entityManager).delete().execute());

        assertThat(affected).isEqualTo(2);
        inTx(entityManager -> {
            assertThat(entityManager.find(ShopOrder.class, 100L)).isNull();
            assertThat(entityManager.find(ShopOrder.class, 101L)).isNull();
            return null;
        });
    }

    @Test
    @DisplayName("没有匹配行时受影响行数是 0")
    void noMatchingRowsAffectNothing() {
        int updated = inTx(entityManager -> orders(entityManager)
                .update()
                .set(Path.of(ShopOrder::getAmount), new BigDecimal("1.00"))
                .where(ShopOrder::getId)
                .eq(999L)
                .execute());
        int deleted = inTx(entityManager ->
                orders(entityManager).delete().where(ShopOrder::getId).eq(999L).execute());

        assertThat(updated).isZero();
        assertThat(deleted).isZero();
    }

    private EntityOperations<ShopOrder> orders(EntityManager entityManager) {
        return new JpaEntityOperationsFactory(entityManager, entityManager.getEntityManagerFactory())
                .operations(ShopOrder.class);
    }
}
