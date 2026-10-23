package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.core.spec.ArithmeticOperand;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Between;
import io.github.nextentity.core.spec.BinaryOperator;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.Ordering;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.Spec;
import io.github.nextentity.core.spec.Window;
import io.github.nextentity.jpa.compile.JpaCompiler;
import io.github.nextentity.jpa.entity.PriceWindow;
import io.github.nextentity.jpa.entity.ShopOrder;
import io.github.nextentity.jpa.exec.JpaExecutor;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `JpaPredicates.between`（`compile/JpaPredicates.java:88-97`）的两条路径。
///
/// `JpaEndToEndTest#predicatesAcrossOperators` 的区间用例取 `[5,13]`，恰好覆盖全部两行，
/// 因此「条件整个丢掉」也照样通过——本类改用**能排除行**的区间来钉住条件是否生效：
/// 区间取空/取单行时，谓词丢失会立刻表现为行数变多。
///
/// 另一条约束来自 `JpaPredicates` 的类注释：双字面量必须走 JPA 的 `Object` 重载以保留
/// 参数绑定，只有真出现表达式时才走 `Expression` 重载。两条路径**渲染出的 SQL 形态不同**
/// （参数 vs 内联字面量），所以字面量路径额外断言 `between ? and ?`：把 `instanceof Literal`
/// 判反的变异体会把字面量塞进 `builder.literal(...)`，参数占位符随之消失。
///
/// 表达式路径的**公开入口**是 `Path#between(Expression, Expression)`（列对列区间），
/// 由 `expressionBoundsThroughPublicApi` 在真实库上跑通；其余用例直接构造 `core.spec` 节点，
/// 用来切出字面量/表达式两个 `instanceof` 侧。
class JpaPredicatesBetweenTest extends JpaTestSupport {

    /// 区间夹具：每行一个值 + 一组边界列，命中与不命中混在一张表里。
    ///
    /// 值取 4/5/10/15/16/30，边界统一 `[5,15]`：命中 10（区间内部）、15（上端闭）、5（下端闭），
    /// 不命中 4 与 16（各差 1）、30（远离区间）。夹具最小/最大值为 4/30，
    /// 放宽对照用它们做两端。
    @BeforeEach
    void seedPriceWindows() {
        inTx(entityManager -> {
            entityManager.createQuery("delete from PriceWindow").executeUpdate();
            entityManager.persist(new PriceWindow(1L, "10", "5", "15"));
            entityManager.persist(new PriceWindow(2L, "30", "5", "15"));
            entityManager.persist(new PriceWindow(3L, "15", "5", "15"));
            entityManager.persist(new PriceWindow(4L, "5", "5", "15"));
            entityManager.persist(new PriceWindow(5L, "4", "5", "15"));
            entityManager.persist(new PriceWindow(6L, "16", "5", "15"));
            return null;
        });
        drainSql();
    }

    /// 夹具行只属于本类：`@BeforeEach` 会重建，这里再显式清一遍，别把行留给后续用例。
    @AfterEach
    void clearPriceWindows() {
        inTx(entityManager -> {
            entityManager.createQuery("delete from PriceWindow").executeUpdate();
            return null;
        });
        drainSql();
    }

    @Test
    @DisplayName("双字面量：区间真的过滤掉 12.30，只留 5.00，且两界是绑定参数")
    void literalBoundsFilterRowsAndBindParameters() {
        QuerySpec spec = query(new Between(
                AttributePath.of(ShopOrder.class, "amount"),
                Literal.of(new BigDecimal("4")),
                Literal.of(new BigDecimal("6"))));

        assertThat(ids(spec)).containsExactly(101L);
        // 字面量路径必须留下两个绑定参数：判反 instanceof 的变异体会把两侧字面量塞进 builder.literal，
        // 渲染成内联的 `between 4 and 6`，参数占位符随之消失
        assertThat(drainSql()).anySatisfy(sql -> assertThat(sql).containsIgnoringCase("amount between ? and ?"));
    }

    @Test
    @DisplayName("双字面量：区间为空时一行都不返回（谓词丢失会返回全部两行）")
    void literalBoundsCanMatchNothing() {
        QuerySpec spec = query(new Between(
                AttributePath.of(ShopOrder.class, "amount"),
                Literal.of(new BigDecimal("50")),
                Literal.of(new BigDecimal("60"))));

        assertThat(ids(spec)).isEmpty();
    }

    @Test
    @DisplayName("单侧表达式：id between id and 100 → 下界是列，只有 id=100 命中")
    void expressionLowerBoundFiltersRows() {
        AttributePath id = AttributePath.of(ShopOrder.class, "id");
        QuerySpec spec = query(new Between(id, id, Literal.of(100L)));

        assertThat(ids(spec)).containsExactly(100L);
        // 表达式路径：只要有一侧不是 Literal 就走 Expression 重载，两侧都内联，语句里不出现参数占位符
        assertThat(drainSql())
                .anySatisfy(
                        sql -> assertThat(sql).containsIgnoringCase("between").doesNotContain("?"));
    }

    @Test
    @DisplayName("两界都是表达式：id between id+1 and id+2 恒假，结果为空")
    void arithmeticBoundsFilterRows() {
        AttributePath id = AttributePath.of(ShopOrder.class, "id");
        QuerySpec spec = query(new Between(
                id,
                new ArithmeticOperand(BinaryOperator.ADD, id, Literal.of(1L)),
                new ArithmeticOperand(BinaryOperator.ADD, id, Literal.of(2L))));

        assertThat(ids(spec)).isEmpty();
        assertThat(drainSql())
                .anySatisfy(
                        sql -> assertThat(sql).containsIgnoringCase("between").doesNotContain("?"));
    }

    /// 表达式形态的 `between`：主语与两端都是列，经公开 api 的 `between(Expression, Expression)` 进入。
    ///
    /// 期望值不是「全表都在区间内」：`seedPriceWindows` 里 6 行只有 3 行的值落在自身边界内，
    /// 且两端各有一行恰好压线（闭区间）。末尾的放宽对照（下界 4、上界 30 = 夹具最小/最大值）
    /// 结果集变大，证明该条件真的在筛行。
    @Test
    @DisplayName("表达式形态 between：value between lower and upper，闭区间两端命中，放宽后行数变多")
    void expressionBoundsThroughPublicApi() {
        List<Long> matched = inTx(entityManager -> operations(entityManager)
                .query()
                .where(Path.of(PriceWindow::getValue))
                .between(Path.of(PriceWindow::getLower), Path.of(PriceWindow::getUpper))
                .orderBy(PriceWindow::getId)
                .asc()
                .list()
                .stream()
                .map(PriceWindow::getId)
                .toList());
        assertThat(matched).containsExactly(1L, 3L, 4L);
        // 两侧都是列 → 没有绑定参数（字面量路径才会渲染 `between ? and ?`）
        assertThat(drainSql())
                .anySatisfy(
                        sql -> assertThat(sql).containsIgnoringCase("between").doesNotContain("?"));

        // 同一区间用字面量形态表达：结果必须与列对列形态一致（两条渲染路径对拍）
        List<Long> literalForm = inTx(entityManager -> operations(entityManager)
                .query()
                .where(Path.of(PriceWindow::getValue))
                .between(new BigDecimal("5"), new BigDecimal("15"))
                .orderBy(PriceWindow::getId)
                .asc()
                .list()
                .stream()
                .map(PriceWindow::getId)
                .toList());
        assertThat(literalForm).containsExactly(1L, 3L, 4L);

        // 放宽对照：下界取夹具最小值 4、上界取最大值 30 → 6 行全中，说明上面的 3 行是条件切出来的
        List<Long> widened = inTx(entityManager -> operations(entityManager)
                .query()
                .where(Path.of(PriceWindow::getValue))
                .between(new BigDecimal("4"), new BigDecimal("30"))
                .orderBy(PriceWindow::getId)
                .asc()
                .list()
                .stream()
                .map(PriceWindow::getId)
                .toList());
        assertThat(widened).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    }

    /// 下界字面量、上界表达式：`instanceof Literal` 的短路顺序里最后一格（`JpaPredicates.java:92`）。
    ///
    /// 下界取 10 使命中集合 `[1,3]` 与「两侧都是列」（`[1,3,4]`）不同，用来证明这一格真的按混合形态
    /// 渲染：把这一格判反、或退化成「两侧都是列」，`value=5` 的第 4 行就会重新命中。
    @Test
    @DisplayName("下界字面量 + 上界表达式：value between 10 and upper_price，命中集合与两侧都是列不同")
    void mixedBoundsLowerLiteralUpperExpression() {
        QuerySpec spec = new QuerySpec(
                PriceWindow.class,
                new EntitySelection(),
                new Between(
                        AttributePath.of(PriceWindow.class, "value"),
                        Literal.of(new BigDecimal("10")),
                        AttributePath.of(PriceWindow.class, "upper")),
                List.of(),
                null,
                List.of(new Ordering(AttributePath.of(PriceWindow.class, "id"), Ordering.Direction.ASC)),
                Window.NONE,
                null);

        assertThat(priceWindowIds(spec)).containsExactly(1L, 3L);
    }

    private List<Long> priceWindowIds(QuerySpec spec) {
        return inTx(entityManager ->
                executor(entityManager)
                        .<PriceWindow>list(compiler(entityManager).compile(spec))
                        .stream()
                        .map(PriceWindow::getId)
                        .toList());
    }

    private EntityOperations<PriceWindow> operations(EntityManager entityManager) {
        return new JpaEntityOperationsFactory(entityManager, entityManager.getEntityManagerFactory())
                .operations(PriceWindow.class);
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
}
