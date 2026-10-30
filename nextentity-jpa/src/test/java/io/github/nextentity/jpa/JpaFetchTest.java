package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.api.Path;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.entity.Branch;
import io.github.nextentity.jpa.entity.Customer;
import io.github.nextentity.jpa.entity.FetchChain;
import io.github.nextentity.jpa.entity.OrderStatus;
import io.github.nextentity.jpa.entity.Organization;
import io.github.nextentity.jpa.entity.ShopOrder;
import io.github.nextentity.jpa.entity.VersionedLedger;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `fetch(...)` 的预取语义：用真实的 SQL 条数证明 N+1 被消除，而不是只断言 SQL 字符串。
///
/// 这里刻意不放宽成"包含 join 就算过"：不 fetch 的对照组必须真的多出懒加载语句，
/// 否则用例在 fetch 失效时仍会通过。
class JpaFetchTest extends JpaTestSupport {

    @Test
    @DisplayName("fetch：列表 + 关联读回都在同一条 SQL 里（N+1 被消除）")
    void fetchLoadsAssociationInSingleStatement() {
        seedSecondOrderWithCustomer();
        drainSql();

        List<ShopOrder> orders = inTx(entityManager -> {
            List<ShopOrder> list = orders(entityManager)
                    .query()
                    .fetch(ShopOrder::getCustomer)
                    .orderBy(ShopOrder::getId)
                    .asc()
                    .list();
            // 事务内真的读关联：fetch 没生效时每读一个非空关联都会补一条 select customer
            list.forEach(order -> {
                if (order.getCustomer() != null) {
                    order.getCustomer().getName();
                }
            });
            return list;
        });

        assertThat(orders).extracting(ShopOrder::getId).containsExactly(100L, 101L, 102L);
        List<String> statements = normalized(drainSql());
        assertThat(statements).as("fetch 生效时列表与关联读回只发一条语句").hasSize(1);
        assertThat(statements.getFirst())
                .startsWith("select ")
                .contains("from shop_order")
                .contains("left join customer");
    }

    @Test
    @DisplayName("不 fetch：同一条查询退化成 1 条列表 + 2 条懒加载（N+1 的对照）")
    void withoutFetchAssociationIsLoadedLazily() {
        seedSecondOrderWithCustomer();
        drainSql();

        inTx(entityManager -> {
            List<ShopOrder> list = orders(entityManager)
                    .query()
                    .orderBy(ShopOrder::getId)
                    .asc()
                    .list();
            list.forEach(order -> {
                if (order.getCustomer() != null) {
                    order.getCustomer().getName();
                }
            });
            return null;
        });

        List<String> statements = normalized(drainSql());
        assertThat(statements).as("不 fetch 时是 1 条列表 + 2 条懒加载").hasSize(3);
        assertThat(statements.stream()
                        .filter(sql -> sql.contains("from customer"))
                        .toList())
                .hasSize(2);
    }

    @Test
    @DisplayName("嵌套 fetch：两段关联的 LEFT JOIN 也在同一条 SQL 里")
    void nestedFetchLoadsSecondLevelAssociation() {
        drainSql();

        inTx(entityManager -> {
            List<ShopOrder> list = orders(entityManager)
                    .query()
                    .fetch(EntityPath.of(ShopOrder::getCustomer).get(Customer::getOrg))
                    .orderBy(ShopOrder::getId)
                    .asc()
                    .list();
            list.forEach(order -> {
                if (order.getCustomer() != null && order.getCustomer().getOrg() != null) {
                    order.getCustomer().getOrg().getName();
                }
            });
            return null;
        });

        List<String> statements = normalized(drainSql());
        assertThat(statements).as("嵌套 fetch 生效时只有一条语句").hasSize(1);
        assertThat(statements.getFirst()).contains("left join customer").contains("left join organization");
    }

    @Test
    @DisplayName("同一关联的父段与嵌套段共享一个 left join，不重复 join")
    void nestedFetchReusesParentJoin() {
        drainSql();

        inTx(entityManager -> {
            orders(entityManager)
                    .query()
                    .fetch(
                            Path.of(ShopOrder::getCustomer),
                            EntityPath.of(ShopOrder::getCustomer).get(Customer::getOrg))
                    .list();
            return null;
        });

        String sql = String.join(" ", normalized(drainSql()));
        assertThat(countOf(sql, "left join customer"))
                .as("父段 fetch 与嵌套 fetch 共用同一个 join：重复 join 会让行数与列数一起放大")
                .isEqualTo(1);
        assertThat(countOf(sql, "left join organization")).isEqualTo(1);
    }

    @Test
    @DisplayName("两条 fetch 共享父前缀：父段只 join 一次（前缀缓存复用同一个 join）")
    void siblingFetchesReuseSharedParentJoin() {
        seedFetchChain();
        drainSql();

        inTx(entityManager -> {
            List<FetchChain> chains = operations(entityManager, FetchChain.class)
                    .query()
                    .fetch(
                            EntityPath.of(FetchChain::getBranch).get(Branch::getFirstOrg),
                            EntityPath.of(FetchChain::getBranch).get(Branch::getSecondOrg))
                    .list();
            chains.forEach(chain -> {
                chain.getBranch().getFirstOrg().getName();
                chain.getBranch().getSecondOrg().getName();
            });
            return null;
        });

        List<String> statements = normalized(drainSql());
        assertThat(statements).as("两条 fetch 也只发一条语句").hasSize(1);
        String sql = statements.getFirst();
        assertThat(countOf(sql, "left join branch"))
                .as("共享父前缀只解析一次：重复 join 会让列与行一起放大")
                .isEqualTo(1);
        assertThat(countOf(sql, "first_org_id")).isEqualTo(1);
        assertThat(countOf(sql, "second_org_id")).isEqualTo(1);
    }

    @Test
    @DisplayName("fetch 标量列：IllegalArgumentException（同一张表里没有可预取的实体）")
    void fetchScalarIsRejected() {
        inTx(entityManager -> {
            assertThatThrownBy(() -> orders(entityManager)
                            .query()
                            .fetch(Path.of(ShopOrder::getStatus))
                            .list())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("fetch 只能指定关联属性")
                    .hasMessageContaining(ShopOrder.class.getName() + ".status");
            return null;
        });
    }

    @Test
    @DisplayName("fetch 集合关联：UnsupportedOperationException（join 会放大行数）")
    void fetchCollectionIsRejected() {
        inTx(entityManager -> {
            assertThatThrownBy(() -> ledgers(entityManager)
                            .query()
                            .fetch(VersionedLedger::getEntries)
                            .list())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("集合关联路径未支持");
            return null;
        });
    }

    /// 追加 102 号订单（customer 11）：让"不 fetch"的对照组有两条懒加载可数。
    private void seedSecondOrderWithCustomer() {
        inTx(entityManager -> {
            entityManager.persist(new ShopOrder(
                    102L,
                    entityManager.getReference(Customer.class, 11L),
                    OrderStatus.CREATED,
                    new BigDecimal("1.00"),
                    null));
            return null;
        });
        drainSql();
    }

    private void seedFetchChain() {
        inTx(entityManager -> {
            Branch branch = new Branch(
                    1L,
                    entityManager.getReference(Organization.class, 1L),
                    entityManager.getReference(Organization.class, 2L));
            entityManager.persist(branch);
            entityManager.persist(new FetchChain(1L, branch));
            return null;
        });
        drainSql();
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }

    private EntityOperations<ShopOrder> orders(EntityManager entityManager) {
        return operations(entityManager, ShopOrder.class);
    }

    private EntityOperations<VersionedLedger> ledgers(EntityManager entityManager) {
        return operations(entityManager, VersionedLedger.class);
    }

    private <T> EntityOperations<T> operations(EntityManager entityManager, Class<T> type) {
        return new JpaEntityOperationsFactory(entityManager, entityManager.getEntityManagerFactory()).operations(type);
    }
}
