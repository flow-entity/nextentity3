package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.Path;
import io.github.nextentity.api.model.Slice;
import io.github.nextentity.api.model.Tuple2;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.entity.Customer;
import io.github.nextentity.jpa.entity.OrderStatus;
import io.github.nextentity.jpa.entity.ShopOrder;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `groupBy` 之后的 `count()` / `exists()`：语义是"组数"/"是否有组"（having 已生效），
/// 而不是行数。Hibernate 通过派生表计数（`select count(*) from (分组查询)`）在一条 SQL 里给出结果。
class JpaGroupCountTest extends JpaTestSupport {

    @BeforeEach
    void seedSecondPaidOrder() {
        inTx(entityManager -> {
            entityManager.persist(new ShopOrder(
                    102L,
                    entityManager.getReference(Customer.class, 11L),
                    OrderStatus.PAID,
                    new BigDecimal("3.00"),
                    null));
            return null;
        });
        drainSql();
    }

    @Test
    @DisplayName("count() 数的是组数，且只有一条派生表计数 SQL")
    void countCountsGroupsInSingleStatement() {
        drainSql();

        long groups = inTx(entityManager ->
                orders(entityManager).query().groupBy(ShopOrder::getStatus).count());

        // PAID(100,102) 与 CREATED(101) 两组
        assertThat(groups).isEqualTo(2);
        List<String> statements = normalized(drainSql());
        assertThat(statements).as("组数统计是单条 SQL，不是把分组全查回来在客户端数").hasSize(1);
        assertThat(statements.getFirst())
                .contains("count(*)")
                .contains("from (select")
                .contains("group by");
    }

    @Test
    @DisplayName("count() 尊重 having：数的是 having 过滤后剩下的组")
    void countRespectsHaving() {
        long paidGroups = inTx(entityManager -> orders(entityManager)
                .query()
                .groupBy(ShopOrder::getStatus)
                .having(Path.of(ShopOrder::getId).count().gt(1L))
                .count());
        long none = inTx(entityManager -> orders(entityManager)
                .query()
                .groupBy(ShopOrder::getStatus)
                .having(Path.of(ShopOrder::getId).count().gt(5L))
                .count());

        assertThat(paidGroups).isEqualTo(1);
        assertThat(none).isZero();
    }

    @Test
    @DisplayName("exists() 回答的是\"有没有满足 having 的组\"")
    void existsAnswersWhetherAnyGroupSurvivesHaving() {
        boolean any = inTx(entityManager ->
                orders(entityManager).query().groupBy(ShopOrder::getStatus).exists());
        boolean paid = inTx(entityManager -> orders(entityManager)
                .query()
                .groupBy(ShopOrder::getStatus)
                .having(Path.of(ShopOrder::getId).count().gt(1L))
                .exists());
        boolean filteredOut = inTx(entityManager -> orders(entityManager)
                .query()
                .groupBy(ShopOrder::getStatus)
                .having(Path.of(ShopOrder::getId).count().gt(5L))
                .exists());

        assertThat(any).isTrue();
        assertThat(paid).isTrue();
        assertThat(filteredOut).isFalse();
    }

    @Test
    @DisplayName("分组 exists 尊重 where，且按 select true + where + group by 渲染")
    void groupExistsRespectsWhereAndRendersGroupBy() {
        drainSql();

        boolean paid = inTx(entityManager -> orders(entityManager)
                .query()
                .where(ShopOrder::getStatus)
                .eq(OrderStatus.PAID)
                .groupBy(ShopOrder::getStatus)
                .exists());

        assertThat(paid).isTrue();
        List<String> statements = normalized(drainSql());
        assertThat(statements).hasSize(1);
        assertThat(statements.getFirst())
                .contains("select true from shop_order")
                .contains("where so1_0.status=?")
                .contains("group by so1_0.status");

        // where 过滤掉所有行 → 没有组
        boolean missing = inTx(entityManager -> orders(entityManager)
                .query()
                .where(ShopOrder::getId)
                .eq(999L)
                .groupBy(ShopOrder::getStatus)
                .exists());
        assertThat(missing).isFalse();
    }

    @Test
    @DisplayName("多个分组键：group by 里每个键都出现")
    void groupExistsRendersEveryGroupKey() {
        drainSql();

        boolean any = inTx(entityManager -> orders(entityManager)
                .query()
                .groupBy(ShopOrder::getStatus, ShopOrder::getAmount)
                .exists());

        assertThat(any).isTrue();
        List<String> statements = normalized(drainSql());
        assertThat(statements).hasSize(1);
        assertThat(statements.getFirst())
                .contains("group by")
                .contains("so1_0.status")
                .contains("so1_0.amount");
    }

    @Test
    @DisplayName("窗口作用在组上：total 是组数、页大小按组生效")
    void windowAppliesToGroups() {
        drainSql();

        Slice<?> slice = inTx(entityManager -> orders(entityManager)
                .query()
                .select(Path.of(ShopOrder::getStatus), Path.of(ShopOrder::getId).count())
                .groupBy(ShopOrder::getStatus)
                .orderBy(Path.of(ShopOrder::getStatus))
                .asc()
                .slice(0, 1));

        assertThat(slice.total()).as("total 是组数（2）而不是行数（3）").isEqualTo(2);
        assertThat(slice.data()).hasSize(1);
        Tuple2<?, ?> firstGroup = (Tuple2<?, ?>) slice.data().getFirst();
        assertThat(firstGroup.get0()).isEqualTo(OrderStatus.CREATED);
        assertThat(firstGroup.get1()).isEqualTo(1L);

        List<String> statements = normalized(drainSql());
        assertThat(statements).as("slice = 计数 + 分页两条").hasSize(2);
        assertThat(statements.get(0)).contains("count(*)").contains("from (select");
        assertThat(statements.get(1)).contains("group by").contains("fetch first");
    }

    @Test
    @DisplayName("没有 groupBy 的聚合形态仍显式拒绝（不是静默换语义）")
    void aggregateWithoutGroupByIsStillRejected() {
        inTx(entityManager -> {
            assertThatThrownBy(() -> orders(entityManager)
                            .query()
                            .select(Path.of(ShopOrder::getId).count())
                            .count())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("分组/聚合规格的 count 未实现");
            assertThatThrownBy(() -> orders(entityManager)
                            .query()
                            .select(Path.of(ShopOrder::getId).count())
                            .exists())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("分组/聚合规格的 exists 未实现");
            // 只有 having、没有 groupBy 也是聚合形态：全表一个分组，同样拒绝
            assertThatThrownBy(() -> orders(entityManager)
                            .query()
                            .having(Path.of(ShopOrder::getId).count().gt(0L))
                            .count())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("分组/聚合规格的 count 未实现");
            return null;
        });
    }

    @Test
    @DisplayName("空结果集：0 组、没有任何组")
    void emptyGroupSetCountsZero() {
        inTx(entityManager -> {
            entityManager.createQuery("delete from ShopOrder").executeUpdate();
            return null;
        });

        long groups = inTx(entityManager ->
                orders(entityManager).query().groupBy(ShopOrder::getStatus).count());
        boolean exists = inTx(entityManager ->
                orders(entityManager).query().groupBy(ShopOrder::getStatus).exists());

        assertThat(groups).isZero();
        assertThat(exists).isFalse();
    }

    private EntityOperations<ShopOrder> orders(EntityManager entityManager) {
        return new JpaEntityOperationsFactory(entityManager, entityManager.getEntityManagerFactory())
                .operations(ShopOrder.class);
    }
}
