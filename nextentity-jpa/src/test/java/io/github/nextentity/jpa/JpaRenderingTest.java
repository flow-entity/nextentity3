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
import org.junit.jupiter.api.Test;

/// 渲染形态断言：把 Hibernate 实际下发的语句归一化后**整句**钉住（{@link JpaTestSupport#normalized}）。
///
/// 早先这里只断言「语句里出现了 `not` / `having` / `min(` 这样的片段」，病根是片段会撞到无关
/// 文本（`so1_0.note` 里就有 `not`，`having` 一测把整个子句写错也能过）；整句之后，列序、别名、
/// 聚合位置、条件是否真的进 SQL 全都在断言里。整句只对 H2 + 当前 Hibernate 成立，因此不进
/// MySQL 通道。
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
        // 整句里一个占位符都没有：两侧都是列（c1_0.org_id 与 o1_0.id），不是把右侧当参数绑进去
        assertThat(normalized(drainSql()))
                .containsExactly(
                        "select so1_0.id,so1_0.amount,so1_0.customer_id,so1_0.note,so1_0.status,so1_0.version from shop_order so1_0"
                                + " left join customer c1_0 on c1_0.id=so1_0.customer_id"
                                + " left join organization o1_0 on o1_0.id=c1_0.organization_id"
                                + " where c1_0.org_id=o1_0.id");
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
        // Hibernate 7 在 H2/MySQL 方言下把 `cb.mod` 渲染成 `%` 运算符（不是 `mod(...)` 函数），
        // 且右侧常量内联成 `2` 而不是绑定参数；整句把「哪一列取模、模几」一起钉住
        assertThat(normalized(drainSql())).containsExactly("select (so1_0.id%2) from shop_order so1_0");
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
        // 整句：两个聚合都在 SQL 侧、顺序与投影顺序一致、聚合的是 amount 这一列
        assertThat(normalized(drainSql()))
                .containsExactly("select min(so1_0.amount),avg(so1_0.amount) from shop_order so1_0");
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
        // 整句：分组列有别名 c0、`having` 上的是 count 聚合而不是别的列、阈值是绑定参数
        assertThat(normalized(drainSql()))
                .containsExactly("select so1_0.status c0,count(so1_0.id) c1 from shop_order so1_0"
                        + " group by c0 having count(so1_0.id)>=?");
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
        // 整句：`not(eq(status, PAID))` 被规约成 `status<>?`（不是 `not(so1_0.status=?)`）。
        // 旧片段 `containsIgnoringCase("not")` 一直是假绿——它命中的是列名 `so1_0.note`。
        assertThat(normalized(drainSql()))
                .containsExactly("select so1_0.id,so1_0.amount,so1_0.customer_id,so1_0.note,so1_0.status,so1_0.version"
                        + " from shop_order so1_0 where so1_0.status<>?");
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
        // 整句：两列都进了 `group by`，且顺序与投影一致（`group by c0,c1` 而不是只剩 c0）
        assertThat(normalized(drainSql()))
                .containsExactly("select so1_0.status c0,so1_0.amount c1 from shop_order so1_0 group by c0,c1");
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
