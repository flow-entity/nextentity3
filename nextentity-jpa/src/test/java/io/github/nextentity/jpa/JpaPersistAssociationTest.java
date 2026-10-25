package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.jpa.entity.Article;
import io.github.nextentity.jpa.entity.Customer;
import io.github.nextentity.jpa.entity.Seal;
import io.github.nextentity.jpa.entity.ShopOrder;
import io.github.nextentity.jpa.entity.Tag;
import io.github.nextentity.jpa.entity.VersionedEntry;
import io.github.nextentity.jpa.entity.VersionedLedger;
import io.github.nextentity.jpa.exec.JpaPersistExecutor;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.ArrayList;
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
/// 覆盖范围：四种关联形态各有夹具——`@ManyToOne`（`ShopOrder#customer`）、
/// `@OneToMany`（`VersionedLedger#entries`）、`@ManyToMany`（`Article#tags`，连接表 `article_tag`）、
/// `@OneToOne`（`Article#seal`，唯一外键 `seal_id`）。
///
/// 四种形态不能互相顶替：`@ManyToOne` / `@OneToOne` 的关联列在**实体表**上，跳过它们表现为
/// "`SET` 少一列"；`@OneToMany` / `@ManyToMany` 的关联在**另一张表**上（子表或连接表），跳过
/// 它们表现为"那条表一行都不许动"——漏掉后者，把集合关联误当属性处理时会静默删光子行。
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

    /// 连接表形态（`@ManyToMany`）与唯一外键形态（`@OneToOne`）各验一次：跳过 `@ManyToMany`
    /// 意味着 `article_tag` 一行都不许动，跳过 `@OneToOne` 意味着 `seal_id` 不进 `SET`。
    ///
    /// 清空 `tags` 并把 `seal` 指向另一行，然后 `update`：要求执行器只发一条打在文章表上的
    /// `UPDATE`，语句里没有 `seal_id`，也**没有任何**语句碰到 `article_tag` 或 `seal` 表；
    /// 重载后标签仍是两个、印章仍是原来那个。
    @Test
    @DisplayName("update：@ManyToMany 连接表与 @OneToOne 外键一律跳过，基本属性照常更新")
    void updateSkipsJoinTableAndOneToOneAssociations() {
        inTx(entityManager -> {
            Tag first = new Tag(1L, "red");
            Tag second = new Tag(2L, "blue");
            entityManager.persist(first);
            entityManager.persist(second);
            Seal original = new Seal(1L, "original");
            entityManager.persist(original);
            entityManager.persist(new Seal(2L, "other"));
            entityManager.persist(new Article(1L, "draft", original, new ArrayList<>(List.of(first, second))));
            return null;
        });
        drainSql();

        Article detached = inTx(entityManager -> {
            Article article = entityManager.find(Article.class, 1L);
            assertThat(article.getTags()).hasSize(2);
            assertThat(article.getSeal().getId()).isEqualTo(1L);
            entityManager.detach(article);
            return article;
        });
        drainSql();

        detached.setName("published");
        detached.setTags(List.of());
        inTx(entityManager -> {
            detached.setSeal(entityManager.getReference(Seal.class, 2L));
            persister(entityManager, Article.class).update(detached);
            return null;
        });

        List<String> statements = normalized(drainSql());
        assertThat(statements.stream().filter(sql -> sql.startsWith("update ")).toList())
                .as("执行器只该发一条 UPDATE，且只打在文章表上")
                .singleElement()
                .satisfies(sql -> assertThat(sql)
                        .contains("update article")
                        .contains("name=?")
                        .doesNotContain("seal_id"));
        assertThat(statements)
                .as("连接表与对端表一条语句都不许出现：既不改连接表，也不动 seal")
                .noneMatch(sql -> sql.contains("article_tag") || sql.contains("seal"));

        inTx(entityManager -> {
            Article reloaded = entityManager.find(Article.class, 1L);
            assertThat(reloaded.getName()).as("基本属性照常写").isEqualTo("published");
            assertThat(reloaded.getTags()).as("连接表被跳过：标签一个都没少").hasSize(2);
            assertThat(reloaded.getSeal().getId()).as("@OneToOne 被跳过：外键仍是 1").isEqualTo(1L);
            return null;
        });
    }

    /// 文章夹具（`Article` / `Tag` / `Seal`）是跨用例共享的表，用完必须清；
    /// 连接表要先清——`article_tag` 的外键指向 `article` 与 `tag`。
    @AfterEach
    void clearArticles() {
        inTx(entityManager -> {
            entityManager.createNativeQuery("delete from article_tag").executeUpdate();
            entityManager.createQuery("delete from Article").executeUpdate();
            entityManager.createQuery("delete from Tag").executeUpdate();
            entityManager.createQuery("delete from Seal").executeUpdate();
            return null;
        });
        drainSql();
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
