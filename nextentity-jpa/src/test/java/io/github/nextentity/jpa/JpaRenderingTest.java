package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.core.spec.AggregateFunction;
import io.github.nextentity.core.spec.AggregateOperand;
import io.github.nextentity.core.spec.AggregateProjection;
import io.github.nextentity.core.spec.ArithmeticOperand;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.BinaryOperator;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.ComputedProjection;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.Logical;
import io.github.nextentity.core.spec.LogicalOperator;
import io.github.nextentity.core.spec.PathProjection;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.Spec;
import io.github.nextentity.core.spec.ValueSelection;
import io.github.nextentity.core.spec.Window;
import io.github.nextentity.jpa.compile.JpaCompiler;
import io.github.nextentity.jpa.entity.OrderStatus;
import io.github.nextentity.jpa.entity.ShopOrder;
import io.github.nextentity.jpa.exec.JpaExecutor;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/// 渲染形态断言：只断言「生成的是不是那个形状」（`containsIgnoringCase` 级别），
/// 不写死整条 SQL——整条 SQL 的快照归 `nextentity-tests-jpa`。
///
/// 覆盖第一梯队落到 jpa 的六项：列对列比较、算术、`min`/`avg`、`having`、`not`、多列分组。
class JpaRenderingTest extends JpaTestSupport {

    @Test
    void columnComparisonRendersColumnsNotParameters() {
        // customer.orgId = customer.org.id：两侧都是列，因此整条语句不应出现参数占位符
        QuerySpec spec = query(new Comparison(
                AttributePath.of(ShopOrder.class, "customer", "orgId"),
                ComparisonOperator.EQ,
                AttributePath.of(ShopOrder.class, "customer", "org", "id")));
        List<ShopOrder> orders = inTx(entityManager ->
                executor(entityManager).list(compiler(entityManager).compile(spec)));

        assertThat(orders).extracting(ShopOrder::getId).containsExactly(100L);
        assertThat(drainSql())
                .anySatisfy(
                        sql -> assertThat(sql).containsIgnoringCase("org_id=").doesNotContain("?"));
    }

    @Test
    void arithmeticRendersModFunction() {
        QuerySpec spec = new QuerySpec(
                ShopOrder.class,
                new ValueSelection(
                        List.of(new ComputedProjection(new ArithmeticOperand(
                                BinaryOperator.MOD, AttributePath.of(ShopOrder.class, "id"), Literal.of(2L)))),
                        false),
                null,
                List.of(),
                null,
                List.of(),
                Window.NONE,
                null);
        List<Long> remainders = inTx(entityManager ->
                executor(entityManager).list(compiler(entityManager).compile(spec)));

        assertThat(remainders).containsExactlyInAnyOrder(0L, 1L);
        // Hibernate 7 在 H2/MySQL 方言下把 `cb.mod` 渲染成 `%` 运算符（不是 `mod(...)` 函数）:
        // 断言「id 对 2 取模」这一形态，不绑定具体函数名
        assertThat(drainSql()).anySatisfy(sql -> assertThat(sql).containsIgnoringCase("id%2"));
    }

    @Test
    void minAndAvgRenderSqlAggregates() {
        AttributePath amount = AttributePath.of(ShopOrder.class, "amount");
        QuerySpec spec = new QuerySpec(
                ShopOrder.class,
                new ValueSelection(
                        List.of(
                                new AggregateProjection(AggregateFunction.MIN, amount),
                                new AggregateProjection(AggregateFunction.AVG, amount)),
                        false),
                null,
                List.of(),
                null,
                List.of(),
                Window.NONE,
                null);
        List<Object[]> rows = inTx(entityManager ->
                executor(entityManager).list(compiler(entityManager).compile(spec)));

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()[0]).isEqualTo(new BigDecimal("5.00"));
        assertThat(rows.getFirst()[1]).isEqualTo(8.65d);
        assertThat(drainSql())
                .anySatisfy(sql -> assertThat(sql).containsIgnoringCase("min(").containsIgnoringCase("avg("));
    }

    @Test
    void havingRendersHavingClause() {
        AttributePath status = AttributePath.of(ShopOrder.class, "status");
        QuerySpec spec = new QuerySpec(
                ShopOrder.class,
                new ValueSelection(
                        List.of(
                                new PathProjection(status),
                                new AggregateProjection(
                                        AggregateFunction.COUNT, AttributePath.of(ShopOrder.class, "id"))),
                        false),
                null,
                List.of(status),
                new Comparison(
                        new AggregateOperand(AggregateFunction.COUNT, AttributePath.of(ShopOrder.class, "id")),
                        ComparisonOperator.GE,
                        Literal.of(1L)),
                List.of(),
                Window.NONE,
                null);
        List<Object[]> rows = inTx(entityManager ->
                executor(entityManager).list(compiler(entityManager).compile(spec)));

        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> assertThat(row[1]).isEqualTo(1L));
        assertThat(drainSql()).anySatisfy(sql -> assertThat(sql).containsIgnoringCase("having"));
    }

    @Test
    void notRendersNotPredicate() {
        QuerySpec spec = query(new Logical(
                LogicalOperator.NOT,
                List.of(new Comparison(
                        AttributePath.of(ShopOrder.class, "status"),
                        ComparisonOperator.EQ,
                        Literal.of(OrderStatus.PAID)))));
        List<ShopOrder> orders = inTx(entityManager ->
                executor(entityManager).list(compiler(entityManager).compile(spec)));

        assertThat(orders).extracting(ShopOrder::getId).containsExactly(101L);
        assertThat(drainSql()).anySatisfy(sql -> assertThat(sql).containsIgnoringCase("not"));
    }

    @Test
    void multiColumnGroupByRendersBothGroupingColumns() {
        AttributePath status = AttributePath.of(ShopOrder.class, "status");
        AttributePath amount = AttributePath.of(ShopOrder.class, "amount");
        QuerySpec spec = new QuerySpec(
                ShopOrder.class,
                new ValueSelection(List.of(new PathProjection(status), new PathProjection(amount)), false),
                null,
                List.of(status, amount),
                null,
                List.of(),
                Window.NONE,
                null);
        List<Object[]> rows = inTx(entityManager ->
                executor(entityManager).list(compiler(entityManager).compile(spec)));

        assertThat(rows).hasSize(2);
        assertThat(drainSql()).anySatisfy(sql -> {
            String lower = sql.toLowerCase(Locale.ROOT);
            int groupBy = lower.indexOf("group by");
            assertThat(groupBy).isGreaterThanOrEqualTo(0);
            assertThat(lower.substring(groupBy)).contains(",");
        });
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
}
