package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.core.spec.ArithmeticOperand;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Between;
import io.github.nextentity.core.spec.BinaryOperator;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.Spec;
import io.github.nextentity.core.spec.Window;
import io.github.nextentity.jpa.compile.JpaCompiler;
import io.github.nextentity.jpa.entity.ShopOrder;
import io.github.nextentity.jpa.exec.JpaExecutor;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
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
class JpaPredicatesBetweenTest extends JpaTestSupport {

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
