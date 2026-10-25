package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.Window;
import io.github.nextentity.jpa.compile.JpaCompiler;
import io.github.nextentity.jpa.entity.Customer;
import io.github.nextentity.jpa.entity.Shipment;
import io.github.nextentity.jpa.entity.ShipmentAddress;
import io.github.nextentity.jpa.entity.ShopOrder;
import io.github.nextentity.jpa.exec.JpaExecutor;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `JpaPaths.navigate` 的两侧（`compile/JpaPaths.java:62-64`）：中段是关联走 `join`，
/// 中段不是关联（`@Embedded`）走 `get`；`path()` 再在解析结果上取最后一段。
///
/// 现有嵌套路径用例（`JpaEndToEndTest#nestedPathUsesLeftJoin`）只走 `@ManyToOne` 链，
/// 因此 `parent.get(attribute)` 那一侧零覆盖：把 `meta.association(...)` 判成恒真，
/// 用 `@Embedded` 的路径会立刻在 Criteria 上抛"不是关联不能 join"。
class JpaPathsNestedTest extends JpaTestSupport {

    /// 嵌入路径夹具：两行只有城市不同，命中一行才说明 `address.city` 真的被渲染成列。
    @BeforeEach
    void seedShipments() {
        inTx(entityManager -> {
            entityManager.createQuery("delete from Shipment").executeUpdate();
            entityManager.persist(new Shipment(1L, new ShipmentAddress("Shanghai")));
            entityManager.persist(new Shipment(2L, new ShipmentAddress("Hangzhou")));
            return null;
        });
        drainSql();
    }

    @AfterEach
    void clearShipments() {
        inTx(entityManager -> {
            entityManager.createQuery("delete from Shipment").executeUpdate();
            return null;
        });
        drainSql();
    }

    @Test
    @DisplayName("先关联再取普通列：EntityPath.of(ShopOrder::getCustomer).get(Customer::getName)")
    void joinedPathThenPlainColumn() {
        assertThat(meta().association(ShopOrder.class, "customer")).isTrue();

        List<Long> ids = inTx(
                entityManager -> new JpaEntityOperationsFactory(entityManager, entityManager.getEntityManagerFactory())
                                .operations(ShopOrder.class)
                                .query()
                                .where(EntityPath.of(ShopOrder::getCustomer).get(Customer::getName))
                                .eq("Alice")
                                .orderBy(ShopOrder::getId)
                                .asc()
                                .list()
                                .stream()
                                .map(ShopOrder::getId)
                                .toList());

        // 101 的 customer 为空：LEFT JOIN 下它不命中，但也不会被 INNER JOIN 吞掉
        assertThat(ids).containsExactly(100L);
        // 整句：中段是关联 → `left join customer`，且条件落在被 join 的别名 c1_0 上
        assertThat(normalized(drainSql()))
                .containsExactly("select so1_0.id,so1_0.amount,so1_0.customer_id,so1_0.note,so1_0.status,so1_0.version"
                        + " from shop_order so1_0 left join customer c1_0 on c1_0.id=so1_0.customer_id"
                        + " where c1_0.name=? order by so1_0.id");
    }

    @Test
    @DisplayName("非关联中段：@Embedded 的第二段路径走 get，语句里不出现 join")
    void embeddedMiddleSegmentUsesGet() {
        assertThat(meta().association(Shipment.class, "address")).isFalse();

        AttributePath city = AttributePath.of(Shipment.class, "address", "city");
        QuerySpec spec = new QuerySpec(
                Shipment.class,
                new EntitySelection(),
                new Comparison(city, ComparisonOperator.EQ, Literal.of("Shanghai")),
                List.of(),
                null,
                List.of(),
                Window.NONE,
                null);

        List<Long> ids = inTx(entityManager ->
                executor(entityManager).<Shipment>list(compiler(entityManager).compile(spec)).stream()
                        .map(Shipment::getId)
                        .toList());

        assertThat(ids).containsExactly(1L);
        // 整句：嵌入路径直接渲染成 shipment 自己的列 `s1_0.city`，语句里没有任何 join
        assertThat(normalized(drainSql()))
                .containsExactly("select s1_0.id,s1_0.city from shipment s1_0 where s1_0.city=?");
    }

    @Test
    @DisplayName("count 走关联路径：join 和 where 都要活到 count 语句里")
    void countThroughJoinedPath() {
        drainSql();

        long count = inTx(entityManager -> operations(entityManager, ShopOrder.class)
                .query()
                .where(EntityPath.of(ShopOrder::getCustomer).get(Customer::getName))
                .eq("Alice")
                .count());

        // 100 号订单的客户是 Alice，101 号客户为空：丢掉 where 会数出 2，丢掉 join 会直接报错
        assertThat(count).isEqualTo(1L);
        assertThat(normalized(drainSql()))
                .as("批次里必须有且只有一条 count 语句——否则断言可能靠别的语句通过")
                .filteredOn(sql -> sql.contains("count("))
                .singleElement()
                .satisfies(sql -> assertThat(sql)
                        .as("count 丢了 join 或 where 都会让这条变成「数全表」")
                        .contains("left join customer c1_0 on c1_0.id=so1_0.customer_id")
                        .contains("where c1_0.name=?"));
    }

    @Test
    @DisplayName("exists 走嵌入路径：命中 true、不命中 false，条件落在仓库自己的列上")
    void existsThroughEmbeddedPath() {
        drainSql();

        boolean hit = inTx(entityManager -> operations(entityManager, Shipment.class)
                .query()
                .where(EntityPath.of(Shipment::getAddress).get(ShipmentAddress::getCity))
                .eq("Shanghai")
                .exists());

        assertThat(hit).isTrue();
        assertThat(normalized(drainSql()))
                .as("批次里必须有且只有一条 exists 语句")
                .filteredOn(sql -> sql.contains("from shipment s1_0"))
                .singleElement()
                .satisfies(
                        sql -> assertThat(sql).as("嵌入路径的 exists 必须把条件渲染成仓库自己的列").contains("where s1_0.city=?"));

        drainSql();

        boolean miss = inTx(entityManager -> operations(entityManager, Shipment.class)
                .query()
                .where(EntityPath.of(Shipment::getAddress).get(ShipmentAddress::getCity))
                .eq("Nowhere")
                .exists());

        // 取不到行时必须是 false：若 exists 退化成「查全表再判非空」就会是 true
        assertThat(miss).isFalse();
    }

    private <T> EntityOperations<T> operations(EntityManager entityManager, Class<T> type) {
        return new JpaEntityOperationsFactory(entityManager, entityManager.getEntityManagerFactory()).operations(type);
    }

    private JpaCompiler compiler(EntityManager entityManager) {
        return new JpaCompiler(entityManager, meta());
    }

    private JpaExecutor executor(EntityManager entityManager) {
        return new JpaExecutor(entityManager, meta());
    }
}
