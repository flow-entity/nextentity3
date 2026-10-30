package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.jpa.entity.Article;
import io.github.nextentity.jpa.entity.Customer;
import io.github.nextentity.jpa.entity.Seal;
import io.github.nextentity.jpa.entity.SealHolder;
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

/// `exec/JpaPersistExecutor.setValues` 的**写关联契约**（3.1.0）：单值关联按关联对象的 `@Id`
/// 写本表外键列，集合关联与 `@OneToOne(mappedBy=...)` 反侧没有本表外键列、仍不写。
///
/// 3.0.0 的行为是「所有关联一律跳过」——改 `order.customer` 后 `update` 既不写 `customer_id`
/// 也不报错，调用方以为改成功了。3.1.0 把它修成 JPA 语义（dirty checking 本来就能写、也能置空），
/// 所以这里要钉四层：
///
/// 1. 可写关联的 `SET` 里**出现**外键列（`customer_id` / `seal_id`），且值来自关联对象的 `@Id`；
/// 2. 关联对象为 `null` 时写 `NULL`（不为了对齐 JDBC 的「null 跳过列」而弱化 JPA 行为）；
/// 3. 集合关联（`@OneToMany` / `@ManyToMany`）仍**一行都不动**（另一张表 / 连接表，没有本表列）；
/// 4. `@OneToOne(mappedBy=...)` 反侧仍不写（外键在对端表）。
///
/// 四种形态不能互相顶替：跳过集合关联时漏守卫会静默删光子行，跳过写入时漏实现会静默丢外键。
class JpaPersistAssociationTest extends JpaTestSupport {

    /// `@ManyToOne`：外键列在实体表上，写侧必须把改过的关联落成 `customer_id`。
    @Test
    @DisplayName("update：@ManyToOne 从关联对象 @Id 写外键列，基本属性照常更新")
    void updateWritesAssociationColumnFromReferencedId() {
        inTx(entityManager -> {
            ShopOrder order = entityManager.find(ShopOrder.class, 100L);
            assertThat(order.getCustomer().getId()).isEqualTo(10L);
            entityManager.detach(order);

            order.setCustomer(new Customer(11L, 2L, "Bob", null));
            order.setAmount(new BigDecimal("99.99"));

            persister(entityManager, ShopOrder.class).update(order);

            // 执行器自己发的那条 UPDATE：SET 里既有基本属性，也有外键列
            List<String> updates = normalized(drainSql()).stream()
                    .filter(sql -> sql.startsWith("update shop_order"))
                    .toList();
            assertThat(updates).hasSize(1);
            assertThat(updates.getFirst())
                    .as("SET 里出现外键列 customer_id，值来自关联对象的 @Id")
                    .contains("customer_id=?")
                    .contains("version=?", "amount=?", "note=?", "status=?")
                    .containsPattern("where .*id=\\? and .*version=\\?");
            return null;
        });
        inTx(entityManager -> {
            ShopOrder reloaded = entityManager.find(ShopOrder.class, 100L);
            // 反向断言：旧行为（跳过关联）下这里会是 10
            assertThat(reloaded.getCustomer().getId()).as("外键真的从 10 改成了 11").isEqualTo(11L);
            assertThat(reloaded.getAmount()).isEqualByComparingTo("99.99");
            assertThat(reloaded.getVersion()).isEqualTo(1);
            return null;
        });
    }

    /// 关联对象为 `null`：写 `NULL` 而不是跳过列。
    ///
    /// JPA 的 dirty checking 本来就能删外键；JDBC 后端因为「null 列无法区分『不改』与『置空』」
    /// 仍跳过，这个差异由 architect 披露，JPA 侧不为对齐而弱化。
    @Test
    @DisplayName("update：关联对象为 null 时写 NULL（清外键），不跳过列")
    void updateWritesNullAssociationAsNull() {
        inTx(entityManager -> {
            ShopOrder order = entityManager.find(ShopOrder.class, 100L);
            entityManager.detach(order);
            order.setCustomer(null);

            persister(entityManager, ShopOrder.class).update(order);

            assertThat(normalized(drainSql()))
                    .filteredOn(sql -> sql.startsWith("update shop_order"))
                    .singleElement()
                    .satisfies(sql -> assertThat(sql).contains("customer_id=?"));
            return null;
        });
        inTx(entityManager -> {
            assertThat(entityManager.find(ShopOrder.class, 100L).getCustomer()).isNull();
            return null;
        });
    }

    /// 唯一外键形态（`@OneToOne` owning）：`seal_id` 是实体表上的一列，改关联必须落成该列。
    ///
    /// 载体特意用没有任何集合属性的 `SealHolder`：`Article` 同时带 `@ManyToMany`，一旦集合
    /// 守卫失效会先在 `CriteriaUpdate.set(集合属性)` 处抛异常，`seal_id` 那条断言根本执行不到。
    @Test
    @DisplayName("update：@OneToOne owning 从关联对象 @Id 写外键列，基本属性照常更新")
    void updateWritesOneToOneForeignKeyFromReferencedId() {
        inTx(entityManager -> {
            entityManager.persist(new Seal(1L, "original"));
            entityManager.persist(new Seal(2L, "other"));
            entityManager.persist(new SealHolder(1L, "draft", entityManager.getReference(Seal.class, 1L)));
            return null;
        });
        drainSql();

        SealHolder detached = inTx(entityManager -> {
            SealHolder holder = entityManager.find(SealHolder.class, 1L);
            assertThat(holder.getSeal().getId()).isEqualTo(1L);
            entityManager.detach(holder);
            return holder;
        });
        drainSql();

        detached.setName("published");
        inTx(entityManager -> {
            detached.setSeal(new Seal(2L, "other"));
            persister(entityManager, SealHolder.class).update(detached);
            return null;
        });

        List<String> statements = normalized(drainSql());
        assertThat(statements.stream().filter(sql -> sql.startsWith("update ")).toList())
                .as("执行器只该发一条 UPDATE，且打在持有者表上")
                .singleElement()
                .satisfies(sql -> assertThat(sql)
                        .contains("update seal_holder")
                        .contains("name=?")
                        .contains("seal_id=?"));
        assertThat(statements)
                .as("不碰对端表：写的是本表外键列，不是 seal 行")
                .noneMatch(sql -> sql.startsWith("update seal ") || sql.startsWith("insert into seal "));

        inTx(entityManager -> {
            SealHolder reloaded = entityManager.find(SealHolder.class, 1L);
            assertThat(reloaded.getName()).isEqualTo("published");
            assertThat(reloaded.getSeal().getId()).as("外键从 1 改成了 2").isEqualTo(2L);
            return null;
        });
    }

    /// `@OneToOne(mappedBy=...)` 反侧：外键在 `seal_holder` 表上，写 `Seal` 时不能写它。
    @Test
    @DisplayName("update：@OneToOne(mappedBy) 反侧不写，也不碰对端表")
    void updateSkipsInverseOneToOneSide() {
        inTx(entityManager -> {
            entityManager.persist(new Seal(1L, "original"));
            entityManager.persist(new Seal(2L, "other"));
            entityManager.persist(new SealHolder(1L, "holder-1", entityManager.getReference(Seal.class, 1L)));
            entityManager.persist(new SealHolder(2L, "holder-2", entityManager.getReference(Seal.class, 2L)));
            return null;
        });
        drainSql();

        Seal detached = inTx(entityManager -> {
            Seal seal = entityManager.find(Seal.class, 1L);
            entityManager.detach(seal);
            return seal;
        });
        drainSql();

        // 反侧关联指向另一个持有者：它不是本表外键列，写它只会造出非法 SQL 或覆盖对端表
        detached.setHolder(new SealHolder(2L, "holder-2", null));
        detached.setCode("renamed");
        inTx(entityManager -> {
            persister(entityManager, Seal.class).update(detached);
            return null;
        });

        List<String> statements = normalized(drainSql());
        assertThat(statements.stream().filter(sql -> sql.startsWith("update ")).toList())
                .singleElement()
                .satisfies(sql -> assertThat(sql)
                        .contains("update seal ")
                        .contains("code=?")
                        .doesNotContain("holder"));
        assertThat(statements).as("反侧没有本表列：对端表一条语句都不许出现").noneMatch(sql -> sql.contains("seal_holder"));

        inTx(entityManager -> {
            assertThat(entityManager.find(Seal.class, 1L).getCode()).isEqualTo("renamed");
            assertThat(entityManager.find(SealHolder.class, 2L).getSeal().getId())
                    .as("对端外键未被改写，仍指向 seal 2")
                    .isEqualTo(2L);
            return null;
        });
    }

    /// 集合关联要多验一层：`entries` 是**另一张表**，所以「不写关联」在这里不是"SET 里少一列"，
    /// 而是"`versioned_entry` 一行都不许动"——既不许 `delete` 也不许 `insert`。
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

    /// 连接表形态（`@ManyToMany`）：`tags` 住在 `article_tag` 里，所以跳过它**不是**"`SET` 少一列"，
    /// 而是"连接表一行都不许动"——既不许 `delete`，也不许重建。
    @Test
    @DisplayName("update：@ManyToMany 连接表一行都不动，基本属性照常更新")
    void updateSkipsJoinTableAssociation() {
        inTx(entityManager -> {
            Tag first = new Tag(1L, "red");
            Tag second = new Tag(2L, "blue");
            entityManager.persist(first);
            entityManager.persist(second);
            entityManager.persist(new Article(1L, "draft", null, new ArrayList<>(List.of(first, second))));
            return null;
        });
        drainSql();

        Article detached = inTx(entityManager -> {
            Article article = entityManager.find(Article.class, 1L);
            assertThat(article.getTags()).hasSize(2);
            entityManager.detach(article);
            return article;
        });
        drainSql();

        detached.setName("published");
        detached.setTags(List.of());
        inTx(entityManager -> {
            persister(entityManager, Article.class).update(detached);
            return null;
        });

        List<String> statements = normalized(drainSql());
        assertThat(statements.stream().filter(sql -> sql.startsWith("update ")).toList())
                .as("执行器只该发一条 UPDATE，且只打在文章表上")
                .singleElement()
                .satisfies(sql -> assertThat(sql).contains("update article").contains("name=?"));
        assertThat(statements).as("连接表一条语句都不许出现：既不删行，也不重建集合").noneMatch(sql -> sql.contains("article_tag"));

        inTx(entityManager -> {
            Article reloaded = entityManager.find(Article.class, 1L);
            assertThat(reloaded.getName()).as("基本属性照常写").isEqualTo("published");
            assertThat(reloaded.getTags()).as("连接表被跳过：标签一个都没少").hasSize(2);
            return null;
        });
    }

    /// 写侧判定本身：`owningAssociation` 只认「本表持外键的单值关联」。
    ///
    /// 这条用例让「哪些关联能写」成为一个被钉住的判定，而不是 `setValues` 里的隐式约定：
    /// 判定若被改成「所有关联都可写」，集合关联会走到 `CriteriaUpdate.set(集合属性)` 抛异常，
    /// 分类表会先红；若被改成「所有关联都不可写」，上面几条写关联用例会红。
    @Test
    @DisplayName("关联 owning 判定：只有本表持外键的单值关联才写")
    void associationOwningClassification() {
        assertThat(meta().owningAssociation(ShopOrder.class, "customer"))
                .as("@ManyToOne")
                .isTrue();
        assertThat(meta().owningAssociation(SealHolder.class, "seal"))
                .as("owning @OneToOne")
                .isTrue();
        assertThat(meta().owningAssociation(Article.class, "seal"))
                .as("Article 的 @OneToOne")
                .isTrue();
        assertThat(meta().owningAssociation(Article.class, "tags"))
                .as("@ManyToMany")
                .isFalse();
        assertThat(meta().owningAssociation(VersionedLedger.class, "entries"))
                .as("@OneToMany")
                .isFalse();
        assertThat(meta().owningAssociation(Seal.class, "holder"))
                .as("@OneToOne(mappedBy) 反侧")
                .isFalse();
        assertThat(meta().owningAssociation(ShopOrder.class, "amount"))
                .as("标量不是关联")
                .isFalse();
        assertThat(meta().owningAssociation(ShopOrder.class, "status")).isFalse();
    }

    /// 文章夹具（`Article` / `Tag` / `Seal` / `SealHolder`）是跨用例共享的表，用完必须清；
    /// 连接表与两个外键表要先清——`article_tag` 的外键指向 `article` 与 `tag`，
    /// `article.seal_id` 与 `seal_holder.seal_id` 都指向 `seal`。
    @AfterEach
    void clearArticles() {
        inTx(entityManager -> {
            entityManager.createNativeQuery("delete from article_tag").executeUpdate();
            entityManager.createQuery("delete from Article").executeUpdate();
            entityManager.createQuery("delete from SealHolder").executeUpdate();
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

    private <T> JpaPersistExecutor<T> persister(EntityManager entityManager, Class<T> type) {
        return new JpaPersistExecutor<>(entityManager, meta(), type);
    }
}
