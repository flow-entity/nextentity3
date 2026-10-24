package io.github.nextentity.core.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.LockMode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// Spec 节点结构的单元测试：不依赖数据库、不依赖 Spring。
///
/// 每个节点至少一条断言，另外覆盖 `QuerySpec` 上两个"纯值变换"（计数/存在性），
/// 它们是 core 对 2.2.3 语义的承诺，属于最容易悄悄漂移的部分。
@DisplayName("Spec 结构")
class SpecStructureTest {

    private static final AttributePath STATUS = AttributePath.of(String.class, "status");
    private static final AttributePath CREATED_AT = AttributePath.of(String.class, "createdAt");
    private static final AttributePath ID = AttributePath.of(String.class, "id");

    /// 无 HAVING 的规格：多数用例只关心 where/groupBy/orderBy/window，HAVING 由专门用例覆盖。
    private static QuerySpec spec(
            Class<?> entityType,
            Selection selection,
            Spec where,
            List<AttributePath> groupBy,
            List<Ordering> orderBy,
            Window window,
            LockMode lock) {
        return new QuerySpec(entityType, selection, where, groupBy, null, orderBy, window, lock);
    }

    @Test
    @DisplayName("单段路径为直接字段，多段路径为嵌套字段")
    void pathDepth() {
        AttributePath nested = AttributePath.of(String.class, "customer", "orgId");

        assertThat(STATUS.isDirect()).isTrue();
        assertThat(STATUS.depth()).isEqualTo(1);
        assertThat(nested.isDirect()).isFalse();
        assertThat(nested.depth()).isEqualTo(2);
        assertThat(nested.names()).containsExactly("customer", "orgId");
    }

    @Test
    @DisplayName("空路径被拒绝")
    void emptyPathRejected() {
        assertThatThrownBy(() -> AttributePath.of(String.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("属性路径不能为空");
    }

    @Test
    @DisplayName("路径入参被复制为不可变列表")
    void pathIsImmutable() {
        assertThat(STATUS.names()).isUnmodifiable();
    }

    @Test
    @DisplayName("等值比较携带值")
    void comparisonCarriesValue() {
        Comparison comparison = new Comparison(STATUS, ComparisonOperator.EQ, Literal.of("CREATED"));

        assertThat(comparison.operator()).isEqualTo(ComparisonOperator.EQ);
        assertThat(comparison.right()).isEqualTo(Literal.of("CREATED"));
        assertThat(comparison.left()).isEqualTo(STATUS);
    }

    @Test
    @DisplayName("比较缺值时报错：有值/无值由节点类型区分，不靠运行期约定")
    void comparisonRequiresValue() {
        // 只断类型时，把 `requireNonNull(value, "value")` 改成「静默存 null」或换成别的
        // NPE 来源（比如 null 路径）都照样通过；消息点名 `value` 才能钉住是「字面量缺值」
        // 这条契约被守住，而不是碰巧抛了个 NPE。
        assertThatThrownBy(() -> new Comparison(STATUS, ComparisonOperator.GT, Literal.of(null)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("value");
    }

    @Test
    @DisplayName("闭区间携带上下界")
    void betweenCarriesBothBounds() {
        Between between = new Between(CREATED_AT, Literal.of("2026-01-01"), Literal.of("2026-02-01"));

        assertThat(between.lower()).isEqualTo(Literal.of("2026-01-01"));
        assertThat(between.upper()).isEqualTo(Literal.of("2026-02-01"));
    }

    @Test
    @DisplayName("集合节点保留 negated 标志且值不可变")
    void inKeepsFlagAndCopiesValues() {
        List<Object> values = new ArrayList<>(List.of(1L, 2L));
        In in = new In(STATUS, values, true);
        values.add(3L);

        assertThat(in.negated()).isTrue();
        assertThat(in.values()).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("空集合不能构造成 IN 节点（builder 层已折成 Constant）")
    void emptyInRejected() {
        assertThatThrownBy(() -> new In(STATUS, List.of(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Constant");
    }

    @Test
    @DisplayName("空值判断只有 negated 标志")
    void nullCheckNegation() {
        assertThat(new NullCheck(STATUS, false).negated()).isFalse();
        assertThat(new NullCheck(STATUS, true).negated()).isTrue();
    }

    @Test
    @DisplayName("恒真/恒假单例")
    void constants() {
        assertThat(Constant.of(true)).isSameAs(Constant.TRUE);
        assertThat(Constant.of(false)).isSameAs(Constant.FALSE);
        assertThat(Constant.FALSE.value()).isFalse();
    }

    @Test
    @DisplayName("AND/OR 组合保留子节点顺序")
    void logicalCombination() {
        Spec left = new Comparison(STATUS, ComparisonOperator.EQ, Literal.of("A"));
        Spec right = new NullCheck(STATUS, true);

        assertThat(Logical.and(left, right).operands()).containsExactly(left, right);
        assertThat(Logical.or(left, right).operator()).isEqualTo(LogicalOperator.OR);
        assertThat(Logical.not(left).operands()).containsExactly(left);
    }

    @Test
    @DisplayName("`isNull().or(路径).le(v)` 解析成 OR(IS NULL, <= v) 的扁平形态")
    void disjunctionOfNullCheckAndComparison() {
        Spec left = new NullCheck(CREATED_AT, false);
        Spec right = new Comparison(CREATED_AT, ComparisonOperator.LE, Literal.of("2026-01-01"));

        Logical disjunction = Logical.or(left, right);

        assertThat(disjunction.operator()).isEqualTo(LogicalOperator.OR);
        assertThat(disjunction.operands()).containsExactly(left, right);
        assertThat(disjunction.operands().get(1)).isInstanceOf(Comparison.class);
    }

    @Test
    @DisplayName("空组合被拒绝")
    void emptyLogicalRejected() {
        assertThatThrownBy(() -> new Logical(LogicalOperator.AND, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少需要一个操作数");
    }

    @Test
    @DisplayName("NOT 只接受一个操作数")
    void notAcceptsSingleOperand() {
        Spec left = new Comparison(STATUS, ComparisonOperator.EQ, Literal.of("A"));
        Spec right = new Comparison(STATUS, ComparisonOperator.EQ, Literal.of("B"));

        assertThatThrownBy(() -> new Logical(LogicalOperator.NOT, List.of(left, right)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOT 只接受一个操作数");
    }

    @Test
    @DisplayName("结构相同的 Spec 相等")
    void structuralEquality() {
        assertThat(new Comparison(STATUS, ComparisonOperator.EQ, Literal.of("A")))
                .isEqualTo(new Comparison(STATUS, ComparisonOperator.EQ, Literal.of("A")))
                .isNotEqualTo(new Comparison(STATUS, ComparisonOperator.EQ, Literal.of("B")));
    }

    @Test
    @DisplayName("投影项：裸路径与聚合")
    void projections() {
        Projection path = new PathProjection(CREATED_AT);
        Projection max = new AggregateProjection(AggregateFunction.MAX, CREATED_AT);

        assertThat(path).isEqualTo(new PathProjection(CREATED_AT));
        assertThat(max).isEqualTo(new AggregateProjection(AggregateFunction.MAX, CREATED_AT));
    }

    @Test
    @DisplayName("聚合投影与分组同时存在（point-mall 6 处 groupBy 的真实形态）")
    void aggregationWithGrouping() {
        ValueSelection selection = new ValueSelection(
                List.of(new PathProjection(STATUS), new AggregateProjection(AggregateFunction.COUNT, ID)), false);
        QuerySpec spec = spec(String.class, selection, null, List.of(STATUS), List.of(), Window.NONE, null);

        assertThat(spec.selection()).isInstanceOf(ValueSelection.class);
        assertThat(((ValueSelection) spec.selection()).items()).hasSize(2);
        assertThat(spec.groupBy()).containsExactly(STATUS);
        assertThat(spec.aggregate()).isTrue();
    }

    @Test
    @DisplayName("表达式查询至少一个投影项，且支持 distinct")
    void valueSelection() {
        ValueSelection selection = new ValueSelection(List.of(new PathProjection(STATUS)), true);

        assertThat(selection.distinct()).isTrue();
        assertThat(selection.items()).hasSize(1);
        assertThatThrownBy(() -> new ValueSelection(List.of(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少需要一个投影项");
    }

    @Test
    @DisplayName("排序项带方向")
    void ordering() {
        Ordering ordering = new Ordering(STATUS, Ordering.Direction.DESC);

        assertThat(ordering.direction()).isEqualTo(Ordering.Direction.DESC);
        assertThat(ordering.path()).isEqualTo(STATUS);
    }

    @Test
    @DisplayName("窗口：默认不限行，负数被拒绝")
    void window() {
        assertThat(Window.NONE.limited()).isFalse();
        assertThat(new Window(10, 20).limited()).isTrue();
        assertThatThrownBy(() -> new Window(-1, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offset");
        assertThatThrownBy(() -> new Window(0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    @DisplayName("初始规格：查实体、无 where、无排序、无窗口、无锁")
    void initialSpec() {
        QuerySpec spec = QuerySpec.of(String.class);

        assertThat(spec.entityType()).isEqualTo(String.class);
        assertThat(spec.selection()).isEqualTo(new EntitySelection());
        assertThat(spec.where()).isNull();
        assertThat(spec.groupBy()).isEmpty();
        assertThat(spec.orderBy()).isEmpty();
        assertThat(spec.window()).isEqualTo(Window.NONE);
        assertThat(spec.lock()).isNull();
        assertThat(spec.aggregate()).isFalse();
    }

    @Test
    @DisplayName("分组或聚合查询被识别为聚合查询（决定分页是否自动加主键排序）")
    void aggregateDetection() {
        QuerySpec grouped =
                spec(String.class, new EntitySelection(), null, List.of(STATUS), List.of(), Window.NONE, null);
        QuerySpec aggregated = spec(
                String.class,
                new ValueSelection(List.of(new AggregateProjection(AggregateFunction.SUM, CREATED_AT)), false),
                null,
                List.of(),
                List.of(),
                Window.NONE,
                null);

        assertThat(grouped.aggregate()).isTrue();
        assertThat(aggregated.aggregate()).isTrue();
    }

    @Test
    @DisplayName("只有 HAVING 的规格也算聚合查询（否则 count 会丢掉 HAVING 静默给出错数）")
    void havingOnlyCountsAsAggregate() {
        Spec having = new Comparison(Literal.of(1), ComparisonOperator.EQ, Literal.of(1));
        QuerySpec spec = new QuerySpec(
                String.class, new EntitySelection(), null, List.of(), having, List.of(), Window.NONE, null);

        assertThat(spec.aggregate()).isTrue();
    }

    @Test
    @DisplayName("计数规格清排序/窗口/锁，保留条件与分组")
    void countSpecClearsPagination() {
        Spec where = new Comparison(STATUS, ComparisonOperator.EQ, Literal.of("A"));
        QuerySpec spec = spec(
                String.class,
                new EntitySelection(),
                where,
                List.of(STATUS),
                List.of(new Ordering(STATUS, Ordering.Direction.DESC)),
                new Window(20, 10),
                LockMode.PESSIMISTIC_WRITE);

        QuerySpec count = spec.forCount();

        assertThat(count.orderBy()).isEmpty();
        assertThat(count.window()).isEqualTo(Window.NONE);
        assertThat(count.lock()).isNull();
        assertThat(count.where()).isEqualTo(where);
        assertThat(count.groupBy()).containsExactly(STATUS);
        assertThat(spec.orderBy()).hasSize(1);
        assertThat(spec.lock()).isEqualTo(LockMode.PESSIMISTIC_WRITE);
    }

    @Test
    @DisplayName("存在性规格清排序、只取 1 行、去锁")
    void existsSpecLimitsToOne() {
        QuerySpec spec = spec(
                String.class,
                new EntitySelection(),
                null,
                List.of(),
                List.of(new Ordering(STATUS, Ordering.Direction.ASC)),
                new Window(0, 50),
                LockMode.PESSIMISTIC_READ);

        QuerySpec exists = spec.forExists();

        assertThat(exists.orderBy()).isEmpty();
        assertThat(exists.window()).isEqualTo(new Window(null, 1));
        assertThat(exists.lock()).isNull();
    }

    @Test
    @DisplayName("规格字段不可变")
    void specIsImmutable() {
        List<Ordering> orderBy = new ArrayList<>(List.of(new Ordering(STATUS, Ordering.Direction.ASC)));
        QuerySpec spec = spec(String.class, new EntitySelection(), null, List.of(), orderBy, Window.NONE, null);
        orderBy.add(new Ordering(CREATED_AT, Ordering.Direction.DESC));

        assertThat(spec.orderBy()).hasSize(1);
        assertThat(spec.orderBy()).isUnmodifiable();
    }
}
