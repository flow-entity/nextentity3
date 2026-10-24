package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.jpa.entity.Customer;
import io.github.nextentity.jpa.entity.ShopOrder;
import io.github.nextentity.jpa.exec.JpaPersistExecutor;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `exec/JpaPersistExecutor.setValues` 跳过关联属性的**可观测后果**：`update(entity)` 不写
/// `@ManyToOne` / 集合关联列（2.2.3 同样只更新基本属性），且**不报错**。
///
/// 这条契约此前没有任何用例钉住，而它是"静默不落库"的典型形状：改 `order.customer` 后调用
/// `update`，SQL 里既没有 `customer_id` 也没有异常，调用方以为改成功了。要钉两层：
///
/// 1. 关联列**不**进 `SET`（改关联 → 库里外键不变）；
/// 2. 同一行的基本属性**照常**更新（跳过关联不能顺带把整条 `UPDATE` 也跳掉）。
///
/// 第 2 层不可省：只断第 1 层时，把 `update` 整个改成空实现也能过。
class JpaPersistAssociationTest extends JpaTestSupport {

    /// 夹具：`JpaTestSupport.resetOrders()` 的 100 号订单属于 10 号客户（Alice），
    /// 11 号客户（Bob）是"另一个可指向的关联目标"。
    @Test
    @DisplayName("update：关联列不进 SET（改 customer 不落库），同一行的基本属性照常更新")
    void updateSkipsAssociationColumns() {
        inTx(entityManager -> {
            ShopOrder order = entityManager.find(ShopOrder.class, 100L);
            assertThat(order.getCustomer().getId()).isEqualTo(10L);
            entityManager.detach(order);

            order.setCustomer(entityManager.getReference(Customer.class, 11L));
            order.setAmount(new BigDecimal("99.99"));

            persister(entityManager).update(order);

            // 执行器自己发的那条 UPDATE：有，且 SET 里没有关联列
            List<String> updates = normalized(drainSql()).stream()
                    .filter(sql -> sql.startsWith("update shop_order"))
                    .toList();
            assertThat(updates).hasSize(1);
            assertThat(updates.getFirst()).doesNotContain("customer_id");
            return null;
        });
        inTx(entityManager -> {
            ShopOrder reloaded = entityManager.find(ShopOrder.class, 100L);
            // 基本属性照常写：跳过关联不等于跳过整条 UPDATE
            assertThat(reloaded.getAmount()).isEqualByComparingTo("99.99");
            assertThat(reloaded.getVersion()).isEqualTo(1);
            // 关联列被跳过：外键仍是 10（Alice），不是 11（Bob）
            assertThat(reloaded.getCustomer().getId()).isEqualTo(10L);
            return null;
        });
    }

    private JpaPersistExecutor<ShopOrder> persister(EntityManager entityManager) {
        return new JpaPersistExecutor<>(entityManager, meta(), ShopOrder.class);
    }
}
