package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityPath;
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
        assertThat(drainSql()).anySatisfy(sql -> assertThat(sql).containsIgnoringCase("join"));
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
        assertThat(drainSql())
                .anySatisfy(
                        sql -> assertThat(sql).containsIgnoringCase("city=?").doesNotContainIgnoringCase(" join "));
    }

    private JpaCompiler compiler(EntityManager entityManager) {
        return new JpaCompiler(entityManager, meta());
    }

    private JpaExecutor executor(EntityManager entityManager) {
        return new JpaExecutor(entityManager, meta());
    }
}
