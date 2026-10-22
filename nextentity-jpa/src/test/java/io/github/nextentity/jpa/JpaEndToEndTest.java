package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.LockMode;
import io.github.nextentity.core.spec.AggregateFunction;
import io.github.nextentity.core.spec.AggregateProjection;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Between;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.Constant;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.In;
import io.github.nextentity.core.spec.Logical;
import io.github.nextentity.core.spec.LogicalOperator;
import io.github.nextentity.core.spec.NullCheck;
import io.github.nextentity.core.spec.Ordering;
import io.github.nextentity.core.spec.PathProjection;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.Spec;
import io.github.nextentity.core.spec.ValueSelection;
import io.github.nextentity.core.spec.Window;
import io.github.nextentity.jpa.compile.JpaCompiler;
import io.github.nextentity.jpa.entity.Customer;
import io.github.nextentity.jpa.entity.OrderStatus;
import io.github.nextentity.jpa.entity.ShopOrder;
import io.github.nextentity.jpa.exec.JpaExecutor;
import io.github.nextentity.jpa.exec.JpaPersistExecutor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/// 端到端：`QuerySpec` → Criteria（JpaCompiler）→ H2 真实执行（JpaExecutor）。
///
/// SQL 文本快照归 `nextentity-tests-jpa`（spring-harness 拥有），这里只验证行为与语义：
/// 等值查询、嵌套路径的 LEFT JOIN、count / exists、单列投影、`@Version` 更新与冲突。
class JpaEndToEndTest extends JpaTestSupport {

    @Test
    void entityListWithEquality() {
        QuerySpec spec = query(
                new Comparison(AttributePath.of(ShopOrder.class, "status"), ComparisonOperator.EQ, OrderStatus.PAID));
        List<ShopOrder> orders = inTx(entityManager ->
                executor(entityManager).list(compiler(entityManager).compile(spec)));
        assertThat(orders).extracting(ShopOrder::getId).containsExactly(100L);
    }

    @Test
    void nestedPathUsesLeftJoin() {
        QuerySpec byOrg = query(
                new Comparison(AttributePath.of(ShopOrder.class, "customer", "orgId"), ComparisonOperator.EQ, 1L));
        List<ShopOrder> matched = inTx(entityManager ->
                executor(entityManager).list(compiler(entityManager).compile(byOrg)));
        assertThat(matched).extracting(ShopOrder::getId).containsExactly(100L);

        // 两级嵌套（point-mall OrderRepository:87 的形态）：customer.org.name
        QuerySpec twoLevels = query(new Comparison(
                AttributePath.of(ShopOrder.class, "customer", "org", "name"), ComparisonOperator.EQ, "Acme"));
        List<ShopOrder> byOrganization = inTx(entityManager ->
                executor(entityManager).list(compiler(entityManager).compile(twoLevels)));
        assertThat(byOrganization).extracting(ShopOrder::getId).containsExactly(100L);

        // customer 为空的订单只有在 LEFT JOIN 下才会被 IS NULL 命中；INNER JOIN 会漏掉它
        QuerySpec orphan = query(new NullCheck(AttributePath.of(ShopOrder.class, "customer", "orgId"), false));
        List<ShopOrder> unmatched = inTx(entityManager ->
                executor(entityManager).list(compiler(entityManager).compile(orphan)));
        assertThat(unmatched).extracting(ShopOrder::getId).containsExactly(101L);
    }

    @Test
    void metaDistinguishesAssociationFromPlainColumn() {
        // orgId 是普通外键列（不 join），org 是关联（join）——join 判据只有这一条
        assertThat(meta().association(Customer.class, "orgId")).isFalse();
        assertThat(meta().association(Customer.class, "org")).isTrue();
        assertThat(meta().association(ShopOrder.class, "customer")).isTrue();
        assertThat(meta().association(ShopOrder.class, "amount")).isFalse();
        assertThat(meta().entityType(ShopOrder.class).id().name()).isEqualTo("id");
        assertThat(meta().entityType(ShopOrder.class).version().name()).isEqualTo("version");
        assertThat(meta().entityType(Customer.class).version()).isNull();
    }

    @Test
    void countAndExists() {
        QuerySpec paid = query(
                new Comparison(AttributePath.of(ShopOrder.class, "status"), ComparisonOperator.EQ, OrderStatus.PAID));
        inTx(entityManager -> {
            JpaCompiler compiler = compiler(entityManager);
            JpaExecutor executor = executor(entityManager);
            assertThat(executor.count(compiler.compile(paid.forCount()))).isEqualTo(1L);
            assertThat(executor.exists(compiler.compile(paid.forExists()))).isTrue();
            assertThat(executor.count(compiler.compile(query(null).forCount()))).isEqualTo(2L);
            return null;
        });
    }

    @Test
    void singleColumnProjection() {
        QuerySpec ids = new QuerySpec(
                ShopOrder.class,
                new ValueSelection(List.of(new PathProjection(AttributePath.of(ShopOrder.class, "id"))), false),
                null,
                List.of(),
                List.of(),
                new Window(0, 10),
                null);
        List<Long> result = inTx(entityManager ->
                executor(entityManager).list(compiler(entityManager).compile(ids)));
        assertThat(result).containsExactlyInAnyOrder(100L, 101L);
    }

    @Test
    void updateBumpsVersionAndDetectsConflict() {
        inTx(entityManager -> {
            ShopOrder order = entityManager.find(ShopOrder.class, 100L);
            entityManager.detach(order);
            order.setAmount(new BigDecimal("99.00"));
            persister(entityManager).update(order);
            assertThat(order.getVersion()).isEqualTo(1);
            return null;
        });
        inTx(entityManager -> {
            ShopOrder reloaded = entityManager.find(ShopOrder.class, 100L);
            assertThat(reloaded.getAmount()).isEqualByComparingTo("99.00");
            assertThat(reloaded.getVersion()).isEqualTo(1);
            return null;
        });
        inTx(entityManager -> {
            ShopOrder stale = entityManager.find(ShopOrder.class, 100L);
            entityManager.detach(stale);
            stale.setVersion(0);
            assertThatThrownBy(() -> persister(entityManager).update(stale))
                    .isInstanceOf(OptimisticLockException.class);
            return null;
        });
    }

    @Test
    void predicatesAcrossOperators() {
        assertThat(ids(query(comparison("status", ComparisonOperator.NE, OrderStatus.PAID))))
                .containsExactly(101L);
        assertThat(ids(query(comparison("amount", ComparisonOperator.GT, new BigDecimal("6")))))
                .containsExactly(100L);
        assertThat(ids(query(comparison("amount", ComparisonOperator.GE, new BigDecimal("5")))))
                .containsExactly(100L, 101L);
        assertThat(ids(query(comparison("amount", ComparisonOperator.LT, new BigDecimal("6")))))
                .containsExactly(101L);
        assertThat(ids(query(comparison("amount", ComparisonOperator.LE, new BigDecimal("5")))))
                .containsExactly(101L);
        // note 是 @Convert 字段：能命中说明值交给 Hibernate 转换，jpa 侧没有再转一次
        assertThat(ids(query(comparison("note", ComparisonOperator.LIKE, "v%"))))
                .containsExactly(100L);

        AttributePath status = AttributePath.of(ShopOrder.class, "status");
        assertThat(ids(query(new In(status, List.<Object>of(OrderStatus.CREATED), false))))
                .containsExactly(101L);
        assertThat(ids(query(new In(status, List.<Object>of(OrderStatus.CREATED), true))))
                .containsExactly(100L);
        assertThat(ids(query(new Between(
                        AttributePath.of(ShopOrder.class, "amount"), new BigDecimal("5"), new BigDecimal("13")))))
                .containsExactly(100L, 101L);
        assertThat(ids(query(Constant.TRUE))).containsExactly(100L, 101L);
        assertThat(ids(query(Constant.FALSE))).isEmpty();

        Spec paid = comparison("status", ComparisonOperator.EQ, OrderStatus.PAID);
        Spec cheap = comparison("amount", ComparisonOperator.LT, new BigDecimal("6"));
        assertThat(ids(query(new Logical(LogicalOperator.OR, List.of(paid, cheap)))))
                .containsExactly(100L, 101L);
        assertThat(ids(query(new Logical(LogicalOperator.AND, List.of(paid, cheap)))))
                .isEmpty();
        assertThat(ids(query(new Logical(LogicalOperator.NOT, List.of(paid))))).containsExactly(101L);
    }

    @Test
    void orderingAndWindow() {
        QuerySpec descending = new QuerySpec(
                ShopOrder.class,
                new EntitySelection(),
                null,
                List.of(),
                List.of(new Ordering(AttributePath.of(ShopOrder.class, "id"), Ordering.Direction.DESC)),
                Window.NONE,
                null);
        assertThat(ids(descending)).containsExactly(101L, 100L);

        QuerySpec secondPage = new QuerySpec(
                ShopOrder.class,
                new EntitySelection(),
                null,
                List.of(),
                List.of(new Ordering(AttributePath.of(ShopOrder.class, "id"), Ordering.Direction.ASC)),
                new Window(1, 1),
                null);
        assertThat(ids(secondPage)).containsExactly(101L);
    }

    @Test
    void groupByAndAggregates() {
        AttributePath status = AttributePath.of(ShopOrder.class, "status");
        AttributePath amount = AttributePath.of(ShopOrder.class, "amount");
        QuerySpec grouped = new QuerySpec(
                ShopOrder.class,
                new ValueSelection(
                        List.of(
                                new PathProjection(status),
                                new AggregateProjection(AggregateFunction.COUNT, amount),
                                new AggregateProjection(AggregateFunction.SUM, amount)),
                        false),
                null,
                List.of(status),
                List.of(),
                Window.NONE,
                null);
        List<Object[]> rows = inTx(entityManager ->
                executor(entityManager).list(compiler(entityManager).compile(grouped)));
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> assertThat(row[1]).isEqualTo(1L));

        QuerySpec maxAmount = new QuerySpec(
                ShopOrder.class,
                new ValueSelection(List.of(new AggregateProjection(AggregateFunction.MAX, amount)), false),
                null,
                List.of(),
                List.of(),
                Window.NONE,
                null);
        List<BigDecimal> max = inTx(entityManager ->
                executor(entityManager).list(compiler(entityManager).compile(maxAmount)));
        assertThat(max).hasSize(1);
        assertThat(max.getFirst()).isEqualByComparingTo("12.30");

        QuerySpec distinctStatus = new QuerySpec(
                ShopOrder.class,
                new ValueSelection(List.of(new AggregateProjection(AggregateFunction.COUNT_DISTINCT, status)), false),
                null,
                List.of(),
                List.of(),
                Window.NONE,
                null);
        List<Long> distinct = inTx(entityManager ->
                executor(entityManager).list(compiler(entityManager).compile(distinctStatus)));
        assertThat(distinct).containsExactly(2L);
    }

    @Test
    void lockAppliesForUpdate() {
        QuerySpec locked = new QuerySpec(
                ShopOrder.class,
                new EntitySelection(),
                comparison("id", ComparisonOperator.EQ, 100L),
                List.of(),
                List.of(),
                Window.NONE,
                LockMode.PESSIMISTIC_WRITE);
        List<ShopOrder> orders = inTx(entityManager ->
                executor(entityManager).list(compiler(entityManager).compile(locked)));
        assertThat(orders).extracting(ShopOrder::getId).containsExactly(100L);
    }

    private static Comparison comparison(String attribute, ComparisonOperator operator, Object value) {
        return new Comparison(AttributePath.of(ShopOrder.class, attribute), operator, value);
    }

    private static List<Long> ids(QuerySpec spec) {
        return inTx(entityManager ->
                executor(entityManager).<ShopOrder>list(compiler(entityManager).compile(spec)).stream()
                        .map(ShopOrder::getId)
                        .toList());
    }

    private static QuerySpec query(Spec where) {
        return new QuerySpec(ShopOrder.class, new EntitySelection(), where, List.of(), List.of(), Window.NONE, null);
    }

    private static JpaCompiler compiler(EntityManager entityManager) {
        return new JpaCompiler(entityManager, meta());
    }

    private static JpaExecutor executor(EntityManager entityManager) {
        return new JpaExecutor(entityManager, meta());
    }

    private static JpaPersistExecutor<ShopOrder> persister(EntityManager entityManager) {
        return new JpaPersistExecutor<>(entityManager, meta(), ShopOrder.class);
    }
}
