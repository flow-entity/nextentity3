package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.LockMode;
import io.github.nextentity.core.spec.AggregateFunction;
import io.github.nextentity.core.spec.AggregateOperand;
import io.github.nextentity.core.spec.AggregateProjection;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Between;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.Constant;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.In;
import io.github.nextentity.core.spec.Literal;
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
import io.github.nextentity.jpa.entity.VersionedEntry;
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
/// 渲染形态（列对列、算术、`min`/`avg`、`having`、`not`、多列分组）见 {@link JpaRenderingTest}。
class JpaEndToEndTest extends JpaTestSupport {

    @Test
    void entityListWithEquality() {
        QuerySpec spec = query(new Comparison(
                AttributePath.of(ShopOrder.class, "status"), ComparisonOperator.EQ, Literal.of(OrderStatus.PAID)));
        List<ShopOrder> orders = inTx(entityManager ->
                executor(entityManager).list(compiler(entityManager).compile(spec)));
        assertThat(orders).extracting(ShopOrder::getId).containsExactly(100L);
    }

    @Test
    void nestedPathUsesLeftJoin() {
        QuerySpec byOrg = query(new Comparison(
                AttributePath.of(ShopOrder.class, "customer", "orgId"), ComparisonOperator.EQ, Literal.of(1L)));
        List<ShopOrder> matched = inTx(entityManager ->
                executor(entityManager).list(compiler(entityManager).compile(byOrg)));
        assertThat(matched).extracting(ShopOrder::getId).containsExactly(100L);

        // 两级嵌套（point-mall OrderRepository:87 的形态）：customer.org.name
        QuerySpec twoLevels = query(new Comparison(
                AttributePath.of(ShopOrder.class, "customer", "org", "name"),
                ComparisonOperator.EQ,
                Literal.of("Acme")));
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
        QuerySpec paid = query(new Comparison(
                AttributePath.of(ShopOrder.class, "status"), ComparisonOperator.EQ, Literal.of(OrderStatus.PAID)));
        inTx(entityManager -> {
            JpaCompiler compiler = compiler(entityManager);
            JpaExecutor executor = executor(entityManager);
            assertThat(executor.count(compiler.compile(paid.forCount()))).isEqualTo(1L);
            assertThat(executor.exists(compiler.compile(paid.forExists()))).isTrue();
            assertThat(executor.count(compiler.compile(query(null).forCount()))).isEqualTo(2L);

            // distinct 的 count 走 count(distinct ...)：2 行 PAID + 1 行 CREATED → 去重后 2
            QuerySpec distinct = new QuerySpec(
                    ShopOrder.class,
                    new ValueSelection(List.of(new PathProjection(AttributePath.of(ShopOrder.class, "status"))), true),
                    null,
                    List.of(),
                    null,
                    List.of(),
                    Window.NONE,
                    null);
            assertThat(executor.count(compiler.compile(distinct.forCount()))).isEqualTo(2L);
            return null;
        });
    }

    /// 不带任何 `where` 的 `exists()`：`exec/JpaExecutor.java:102` 的 `where == null` 一侧。
    ///
    /// 此前只用「带条件」的规格测过 exists，因此「无条件时整表有行即真」与「空表为假」都没钉住，
    /// 把该分支判反（`where != null`）也照样通过。空表侧先断言计数为 0，避免表里其实有行时
    /// 把用例读成通过。
    @Test
    void existsWithoutWhereLooksAtWholeTable() {
        inTx(entityManager -> {
            JpaCompiler compiler = compiler(entityManager);
            JpaExecutor executor = executor(entityManager);

            assertThat(executor.exists(compiler.compile(query(null).forExists())))
                    .isTrue();
            // 条件被整段跳过：语句里没有 WHERE，而不是渲染成一个恒真条件
            assertThat(drainSql()).anySatisfy(sql -> assertThat(sql)
                    .containsIgnoringCase("from shop_order")
                    .doesNotContainIgnoringCase(" where "));

            QuerySpec emptyTable = QuerySpec.of(VersionedEntry.class);
            assertThat(executor.count(compiler.compile(emptyTable.forCount()))).isZero();
            assertThat(executor.exists(compiler.compile(emptyTable.forExists())))
                    .isFalse();
            return null;
        });
    }

    /// `count()` 的两条契约拒绝（`exec/JpaExecutor.java:58`、`:69`）。
    ///
    /// 两种形状都能从公开 api 写出来（`groupBy(...).count()`、`distinct().select(a, b).count()`），
    /// 因此拒绝分支必须钉住：静默近似会给出与查询不一致的行数。
    @Test
    void countRejectsAggregateAndMultiColumnDistinct() {
        AttributePath status = AttributePath.of(ShopOrder.class, "status");
        AttributePath amount = AttributePath.of(ShopOrder.class, "amount");
        QuerySpec grouped = new QuerySpec(
                ShopOrder.class,
                new ValueSelection(List.of(new PathProjection(status)), false),
                null,
                List.of(status),
                null,
                List.of(),
                Window.NONE,
                null);
        QuerySpec multiColumnDistinct = new QuerySpec(
                ShopOrder.class,
                new ValueSelection(List.of(new PathProjection(status), new PathProjection(amount)), true),
                null,
                List.of(),
                null,
                List.of(),
                Window.NONE,
                null);

        inTx(entityManager -> {
            JpaCompiler compiler = compiler(entityManager);
            JpaExecutor executor = executor(entityManager);
            assertThatThrownBy(() -> executor.count(compiler.compile(grouped.forCount())))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessage("分组/聚合规格的 count 未实现（2.2.3 走子查询计数：" + "io.github.nextentity.jpa.entity.ShopOrder）");
            assertThatThrownBy(() -> executor.count(compiler.compile(multiColumnDistinct.forCount())))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessage(
                            "多列 distinct 规格的 count 未实现（列数 2，方言语义不一致）：" + "io.github.nextentity.jpa.entity.ShopOrder");
            return null;
        });
    }

    /// `NullCheck` 的 `is not null` 一侧（`compile/JpaPredicates.java:109`）；`is null` 一侧由
    /// `nestedPathUsesLeftJoin` 覆盖（那里用 customer 为空的订单）。
    @Test
    void isNotNullMatchesRowsWithNonNullAssociation() {
        assertThat(ids(query(new NullCheck(AttributePath.of(ShopOrder.class, "customer", "orgId"), true))))
                .containsExactly(100L);
    }

    @Test
    void existsRejectsGroupedAndHavingSpecs() {
        AttributePath status = AttributePath.of(ShopOrder.class, "status");
        // groupBy：exists 只渲染 where，会把"有没有组"当成"有没有行"——直接拒绝，不给错答案
        QuerySpec grouped = new QuerySpec(
                ShopOrder.class,
                new ValueSelection(List.of(new PathProjection(status)), false),
                null,
                List.of(status),
                null,
                List.of(),
                Window.NONE,
                null);
        // having：把全部组都过滤掉时，只渲染 where 的 exists 会返回 true（与查询相反）
        QuerySpec havingOnly = new QuerySpec(
                ShopOrder.class,
                new EntitySelection(),
                null,
                List.of(),
                new Comparison(
                        new AggregateOperand(AggregateFunction.COUNT, AttributePath.of(ShopOrder.class, "id")),
                        ComparisonOperator.GE,
                        Literal.of(999L)),
                List.of(),
                Window.NONE,
                null);

        inTx(entityManager -> {
            JpaCompiler compiler = compiler(entityManager);
            JpaExecutor executor = executor(entityManager);
            assertThatThrownBy(() -> executor.exists(compiler.compile(grouped.forExists())))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("exists");
            assertThatThrownBy(() -> executor.exists(compiler.compile(havingOnly.forExists())))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("exists");

            // 普通条件查询的 exists 行为不变
            assertThat(executor.exists(
                            compiler.compile(query(comparison("status", ComparisonOperator.EQ, OrderStatus.PAID))
                                    .forExists())))
                    .isTrue();
            assertThat(executor.exists(compiler.compile(query(comparison("id", ComparisonOperator.EQ, 99999L))
                            .forExists())))
                    .isFalse();
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
                null,
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
        assertThat(ids(query(new In(status, List.of(OrderStatus.CREATED), false))))
                .containsExactly(101L);
        assertThat(ids(query(new In(status, List.of(OrderStatus.CREATED), true))))
                .containsExactly(100L);
        assertThat(ids(query(new Between(
                        AttributePath.of(ShopOrder.class, "amount"),
                        Literal.of(new BigDecimal("5")),
                        Literal.of(new BigDecimal("13"))))))
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
                null,
                List.of(new Ordering(AttributePath.of(ShopOrder.class, "id"), Ordering.Direction.DESC)),
                Window.NONE,
                null);
        assertThat(ids(descending)).containsExactly(101L, 100L);

        QuerySpec secondPage = new QuerySpec(
                ShopOrder.class,
                new EntitySelection(),
                null,
                List.of(),
                null,
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
                null,
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
                null,
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
                null,
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
                null,
                List.of(),
                Window.NONE,
                LockMode.PESSIMISTIC_WRITE);
        List<ShopOrder> orders = inTx(entityManager ->
                executor(entityManager).list(compiler(entityManager).compile(locked)));
        assertThat(orders).extracting(ShopOrder::getId).containsExactly(100L);
    }

    private static Comparison comparison(String attribute, ComparisonOperator operator, Object value) {
        return new Comparison(AttributePath.of(ShopOrder.class, attribute), operator, Literal.of(value));
    }

    private List<Long> ids(QuerySpec spec) {
        return inTx(entityManager ->
                executor(entityManager).<ShopOrder>list(compiler(entityManager).compile(spec)).stream()
                        .map(ShopOrder::getId)
                        .toList());
    }

    private static QuerySpec query(Spec where) {
        return new QuerySpec(
                ShopOrder.class, new EntitySelection(), where, List.of(), null, List.of(), Window.NONE, null);
    }

    private JpaCompiler compiler(EntityManager entityManager) {
        return new JpaCompiler(entityManager, meta());
    }

    private JpaExecutor executor(EntityManager entityManager) {
        return new JpaExecutor(entityManager, meta());
    }

    private JpaPersistExecutor<ShopOrder> persister(EntityManager entityManager) {
        return new JpaPersistExecutor<>(entityManager, meta(), ShopOrder.class);
    }
}
