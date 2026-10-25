package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.jpa.entity.Customer;
import io.github.nextentity.jpa.entity.ShopOrder;
import io.github.nextentity.jpa.entity.VersionedEntry;
import io.github.nextentity.jpa.entity.VersionedLedger;
import io.github.nextentity.jpa.exec.JpaPersistExecutor;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `exec/JpaPersistExecutor.setValues` 跳过关联属性的**可观测后果**：`update(entity)` 不写
/// `@ManyToOne` 关联列（2.2.3 同样只更新基本属性），且**不报错**。
///
/// 这条契约此前没有任何用例钉住，而它是"静默不落库"的典型形状：改 `order.customer` 后调用
/// `update`，SQL 里既没有 `customer_id` 也没有异常，调用方以为改成功了。要钉两层：
///
/// 1. 关联列**不**进 `SET`（改关联 → 库里外键不变）；
/// 2. 同一行的基本属性**照常**更新（跳过关联不能顺带把整条 `UPDATE` 也跳掉）。
///
/// 第 2 层不可省：只断第 1 层时，把 `update` 整个改成空实现也能过。
///
/// 覆盖范围：`@ManyToOne`（`ShopOrder#customer`）与 `@OneToMany`（`VersionedLedger#entries`）
/// 两种关联形态；`@OneToOne` 与 `@ManyToMany` 没有夹具，仍是未验证项——它们走同一条
/// `meta.association(...)` 分支，但别当成已覆盖。
class JpaPersistAssociationTest extends JpaTestSupport {

    /// 集合关联要多验一层：`entries` 是**另一张表**，所以「跳过关联」在这里不是"SET 里少一列"，
    /// 而是"`versioned_entry` 一行都不许动"——既不许 `delete` 也不许 `insert`。
    /// 把 `entries` 清空后调用 `update`，集合关联若被误当成属性处理，会看到子行被删或
    /// 整表重建；现在要求一条都不出现。
    @Test
    @DisplayName("update：集合关联 @OneToMany 一行都不动，同一行的基本属性照常更新")
    void updateSkipsCollectionAssociation() {
        inTx(entityManager -> {
            // 版本列必须留 null：`@Version` 实体一旦带着非 null 版本就会被 Hibernate 当成脱管实例，
            // `persist` 直接抛 `EntityExistsException`。落库后 Hibernate 自己把它置 0。
            VersionedLedger ledger = new VersionedLedger(1L, "opening", null);
            entityManager.persist(ledger);
            entityManager.persist(new VersionedEntry(11L, "first", ledger));
            entityManager.persist(new VersionedEntry(12L, "second", ledger));
            return null;
        });
        drainSql();

        VersionedLedger detached = inTx(entityManager -> {
            VersionedLedger ledger = entityManager.find(VersionedLedger.class, 1L);
            assertThat(ledger.getEntries()).hasSize(2);
            entityManager.detach(ledger);
            return ledger;
        });
        drainSql();

        detached.setName("renamed");
        detached.setEntries(List.of());
        inTx(entityManager -> {
            persister(entityManager, VersionedLedger.class).update(detached);
            return null;
        });

        List<String> statements = normalized(drainSql());
        assertThat(statements.stream().filter(sql -> sql.startsWith("update ")).toList())
                .as("执行器只该发一条 UPDATE，且只打在台账表上")
                .singleElement()
                .satisfies(sql -> assertThat(sql)
                        .contains("update versioned_ledger")
                        .contains("name=?")
                        .doesNotContain("versioned_entry"));
        assertThat(statements).as("集合关联不产生任何语句：既不删子行，也不重建集合").noneMatch(sql -> sql.contains("versioned_entry"));

        inTx(entityManager -> {
            VersionedLedger reloaded = entityManager.find(VersionedLedger.class, 1L);
            assertThat(reloaded.getName()).as("基本属性照常写").isEqualTo("renamed");
            assertThat(reloaded.getVersion()).as("版本照常自增").isEqualTo(1L);
            assertThat(reloaded.getEntries()).as("集合关联被跳过：子行一条都没少").hasSize(2);
            return null;
        });
    }

    /// 台账夹具（`VersionedLedger` / `VersionedEntry`）是跨用例共享的表，用完必须清。
    @AfterEach
    void clearLedgers() {
        inTx(entityManager -> {
            entityManager.createQuery("delete from VersionedEntry").executeUpdate();
            entityManager.createQuery("delete from VersionedLedger").executeUpdate();
            return null;
        });
        drainSql();
    }

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

            persister(entityManager, ShopOrder.class).update(order);

            // 执行器自己发的那条 UPDATE：有，且 SET 子句里只有基本属性
            List<String> updates = normalized(drainSql()).stream()
                    .filter(sql -> sql.startsWith("update shop_order"))
                    .toList();
            assertThat(updates).hasSize(1);
            assertThat(updates.getFirst())
                    .as("SET 子句只含基本属性：关联列 customer_id 不在其中")
                    .doesNotContain("customer_id")
                    .contains("version=?", "amount=?", "note=?", "status=?")
                    .containsPattern("where .*id=\\? and .*version=\\?");
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

    private <T> JpaPersistExecutor<T> persister(EntityManager entityManager, Class<T> type) {
        return new JpaPersistExecutor<>(entityManager, meta(), type);
    }
}
