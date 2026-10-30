package io.github.nextentity.core.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.LockMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/// Spec 节点结构的单元测试：不依赖数据库、不依赖 Spring。
///
/// 每个节点至少一条断言，另外覆盖 `QuerySpec` 上两个"纯值变换"（计数/存在性），
/// 它们是 core 对 2.2.3 语义的承诺，属于最容易悄悄漂移的部分。
@DisplayName("Spec 结构")
class SpecStructureTest {

    private static final AttributePath STATUS = AttributePath.of(String.class, "status");
    private static final AttributePath CREATED_AT = AttributePath.of(String.class, "createdAt");
    private static final AttributePath ID = AttributePath.of(String.class, "id");

    /// 这些用例只关心「列集与去重标志」，装配方式不参与 `ValueSelection` 的等价性，
    /// 因此共用一个取首列的装配器即可——需要真装配结果的用例在 `ProjectionMappersTest` 里。
    private static final RowMapper<Object> FIRST_COLUMN = row -> row.get(0);

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

    /// 带分组与 HAVING 的规格：只关心分组计数这一节，其余字段取缺省。
    private static QuerySpec grouped(Selection selection, List<AttributePath> groupBy, Spec having) {
        return new QuerySpec(
                String.class,
                selection,
                null,
                groupBy,
                having,
                List.of(new Ordering(STATUS, Ordering.Direction.ASC)),
                Window.NONE,
                null);
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
        // `isUnmodifiable()` 只认 JDK/Guava 的那几种包装类，认不出本仓 `ImmutableList` 的实现，
        // 所以直接钉行为：写入必被拒绝。
        assertThatThrownBy(() -> STATUS.names().add("x")).isInstanceOf(UnsupportedOperationException.class);
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
    @DisplayName("In#of：任意集合一次收成不可变列表（元素可为 null）")
    void inOfTakesAnyCollection() {
        Set<Object> values = new LinkedHashSet<>(Arrays.asList(null, 5L));
        In in = In.of(STATUS, values, false);

        assertThat(in.values()).containsExactly(null, 5L);
        assertThatThrownBy(() -> in.values().add(6L)).isInstanceOf(UnsupportedOperationException.class);
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
                List.of(new PathProjection(STATUS), new AggregateProjection(AggregateFunction.COUNT, ID)),
                false,
                FIRST_COLUMN);
        QuerySpec spec = spec(String.class, selection, null, List.of(STATUS), List.of(), Window.NONE, null);

        assertThat(spec.selection()).isInstanceOf(ValueSelection.class);
        assertThat(((ValueSelection) spec.selection()).items()).hasSize(2);
        assertThat(spec.groupBy()).containsExactly(STATUS);
        assertThat(spec.aggregate()).isTrue();
    }

    @Test
    @DisplayName("表达式查询至少一个投影项，且支持 distinct")
    void valueSelection() {
        ValueSelection selection = new ValueSelection(List.of(new PathProjection(STATUS)), true, FIRST_COLUMN);

        assertThat(selection.distinct()).isTrue();
        assertThat(selection.items()).hasSize(1);
        assertThatThrownBy(() -> new ValueSelection(List.of(), false, FIRST_COLUMN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少需要一个投影项");
    }

    @Test
    @DisplayName("值查询的等价性只看列集与去重标志，装配方式不参与（否则同一份 SQL 的两个规格不相等）")
    void valueSelectionEqualityIgnoresRowMapper() {
        RowMapper<Object> other = row -> row.get(0);

        assertThat(new ValueSelection(List.of(new PathProjection(STATUS)), false, FIRST_COLUMN))
                .isEqualTo(new ValueSelection(List.of(new PathProjection(STATUS)), false, other))
                .hasSameHashCodeAs(new ValueSelection(List.of(new PathProjection(STATUS)), false, other))
                .isNotEqualTo(new ValueSelection(List.of(new PathProjection(STATUS)), true, FIRST_COLUMN))
                .isNotEqualTo(new ValueSelection(List.of(new PathProjection(ID)), false, FIRST_COLUMN));
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
                new ValueSelection(
                        List.of(new AggregateProjection(AggregateFunction.SUM, CREATED_AT)), false, FIRST_COLUMN),
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
        assertThatThrownBy(() -> spec.orderBy().add(new Ordering(CREATED_AT, Ordering.Direction.ASC)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ------------------------------------------- 分组之后的计数/存在性
    //
    // `groupBy(...)` 之后 `count()` 数的是组数、`exists()` 问的是"有没有组"，两者都必须把
    // `groupBy`/`having` 一起交给后端；数错层（组数 vs 总行数）是静默错数，看不出来。

    @Test
    @DisplayName("分组计数：投影换成分组键，保留分组与 HAVING，清排序窗口锁")
    void groupCountCountsGroups() {
        Selection aggregates = new ValueSelection(
                List.of(new AggregateProjection(AggregateFunction.COUNT, STATUS)), false, FIRST_COLUMN);
        Comparison having = new Comparison(STATUS, ComparisonOperator.EQ, Literal.of("A"));

        QuerySpec counting = grouped(aggregates, List.of(STATUS, ID), having).forGroupCount();

        assertThat(counting.countScope()).isEqualTo(CountScope.GROUPS);
        assertThat(counting.groupBy()).containsExactly(STATUS, ID);
        assertThat(counting.having()).isEqualTo(having);
        assertThat(counting.orderBy()).isEmpty();
        assertThat(counting.window()).isEqualTo(Window.NONE);
        assertThat(counting.lock()).isNull();
        assertThat(counting.fetch()).isEmpty();
        // 子查询只选分组键：沿用 `count(...)` 投影会把组内行数再数一遍。
        assertThat(counting.selection()).isInstanceOf(ValueSelection.class);
        assertThat(((ValueSelection) counting.selection()).items())
                .containsExactly(new PathProjection(STATUS), new PathProjection(ID));
    }

    @Test
    @DisplayName("分组存在性：限 1 行、保留分组与 HAVING、清排序")
    void groupExistsLimitsToOne() {
        Comparison having = new Comparison(STATUS, ComparisonOperator.EQ, Literal.of("A"));

        QuerySpec probing =
                grouped(new EntitySelection(), List.of(STATUS), having).forGroupExists();

        assertThat(probing.countScope()).isEqualTo(CountScope.GROUPS);
        assertThat(probing.groupBy()).containsExactly(STATUS);
        assertThat(probing.having()).isEqualTo(having);
        assertThat(probing.window()).isEqualTo(new Window(null, 1));
        assertThat(probing.orderBy()).isEmpty();
    }

    @Test
    @DisplayName("分组计数/存在性：没有分组键就拒绝")
    void groupingRequired() {
        QuerySpec plain = spec(String.class, new EntitySelection(), null, List.of(), List.of(), Window.NONE, null);

        assertThatThrownBy(plain::forGroupCount)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("groupBy");
        assertThatThrownBy(plain::forGroupExists)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("groupBy");
    }

    @Test
    @DisplayName("计数对象缺省是行：历史构造器与 forCount()/forExists() 都保持 ROWS")
    void countScopeDefaultsToRows() {
        QuerySpec plain = spec(String.class, new EntitySelection(), null, List.of(), List.of(), Window.NONE, null);

        assertThat(plain.countScope()).isEqualTo(CountScope.ROWS);
        assertThat(plain.forCount().countScope()).isEqualTo(CountScope.ROWS);
        assertThat(plain.forExists().countScope()).isEqualTo(CountScope.ROWS);
        // 计数/存在性规格不该丢弃分组信息：丢了就退化成"总行数/是否有行"。
        assertThat(grouped(new EntitySelection(), List.of(STATUS), null)
                        .forCount()
                        .groupBy())
                .containsExactly(STATUS);
    }

    // ------------------------------------------- 构造期契约：必填字段与访问器
    //
    // 这一节只管「字段在构造期被拒绝、访问器原样交回」，语义仍由上面各节负责。
    // 它存在的理由是变异测试：`Objects.requireNonNull(x, "x")` 的变异体是**整句删除**，
    // 删掉之后字段静默存 null，异常改由后续第一次使用它的地方抛出——`List.copyOf(null)`
    // 同样抛 NullPointerException、`list.isEmpty()` 同样抛，所以只断言「抛了个 NPE」
    // 什么也证明不了。每一行都必须断到 `requireNonNull` 里那个字段名。

    /// 每个节点的每个必填字段：单独传一次 `null`。
    ///
    /// 表驱动而不是逐个 `@Test`：这张表一行对应一个变异体，评审时能直接数出覆盖面。
    static Stream<Arguments> requiredFields() {
        Literal value = Literal.of("A");
        return Stream.of(
                Arguments.of(
                        "QuerySpec.entityType",
                        (Executable) () -> new QuerySpec(
                                null, new EntitySelection(), null, List.of(), null, List.of(), Window.NONE, null),
                        "entityType"),
                Arguments.of(
                        "QuerySpec.selection",
                        (Executable) () ->
                                new QuerySpec(String.class, null, null, List.of(), null, List.of(), Window.NONE, null),
                        "selection"),
                Arguments.of(
                        "QuerySpec.groupBy",
                        (Executable) () -> new QuerySpec(
                                String.class, new EntitySelection(), null, null, null, List.of(), Window.NONE, null),
                        "groupBy"),
                Arguments.of(
                        "QuerySpec.orderBy",
                        (Executable) () -> new QuerySpec(
                                String.class, new EntitySelection(), null, List.of(), null, null, Window.NONE, null),
                        "orderBy"),
                Arguments.of(
                        "QuerySpec.countScope",
                        (Executable) () -> new QuerySpec(
                                String.class,
                                new EntitySelection(),
                                null,
                                List.of(),
                                null,
                                List.of(),
                                Window.NONE,
                                null,
                                List.of(),
                                null),
                        "countScope"),
                Arguments.of(
                        "QuerySpec.fetch",
                        (Executable) () -> new QuerySpec(
                                String.class,
                                new EntitySelection(),
                                null,
                                List.of(),
                                null,
                                List.of(),
                                Window.NONE,
                                null,
                                null),
                        "fetch"),
                Arguments.of(
                        "QuerySpec.window",
                        (Executable) () -> new QuerySpec(
                                String.class, new EntitySelection(), null, List.of(), null, List.of(), null, null),
                        "window"),
                Arguments.of(
                        "ValueSelection.items",
                        (Executable) () -> new ValueSelection(null, false, FIRST_COLUMN),
                        "items"),
                Arguments.of(
                        "ValueSelection.rowMapper",
                        (Executable) () -> new ValueSelection(List.of(new PathProjection(STATUS)), false, null),
                        "rowMapper"),
                Arguments.of(
                        "AttributePath.entityType",
                        (Executable) () -> new AttributePath(null, List.of("status")),
                        "entityType"),
                Arguments.of("AttributePath.names", (Executable) () -> new AttributePath(String.class, null), "names"),
                Arguments.of(
                        "Logical.operator",
                        (Executable) () -> new Logical(null, List.of(new Constant(true))),
                        "operator"),
                Arguments.of("Logical.operands", (Executable) () -> new Logical(LogicalOperator.AND, null), "operands"),
                Arguments.of(
                        "Comparison.left",
                        (Executable) () -> new Comparison(null, ComparisonOperator.EQ, value),
                        "left"),
                Arguments.of("Comparison.operator", (Executable) () -> new Comparison(STATUS, null, value), "operator"),
                Arguments.of(
                        "Comparison.right",
                        (Executable) () -> new Comparison(STATUS, ComparisonOperator.EQ, null),
                        "right"),
                Arguments.of("Between.subject", (Executable) () -> new Between(null, value, value), "subject"),
                Arguments.of("Between.lower", (Executable) () -> new Between(STATUS, null, value), "lower"),
                Arguments.of("Between.upper", (Executable) () -> new Between(STATUS, value, null), "upper"),
                Arguments.of("In.path", (Executable) () -> new In(null, List.of("A"), false), "path"),
                Arguments.of("In.values", (Executable) () -> new In(STATUS, null, false), "values"),
                Arguments.of("NullCheck.path", (Executable) () -> new NullCheck(null, false), "path"),
                Arguments.of(
                        "ArithmeticOperand.operator",
                        (Executable) () -> new ArithmeticOperand(null, value, value),
                        "operator"),
                Arguments.of(
                        "ArithmeticOperand.left",
                        (Executable) () -> new ArithmeticOperand(BinaryOperator.ADD, null, value),
                        "left"),
                Arguments.of(
                        "ArithmeticOperand.right",
                        (Executable) () -> new ArithmeticOperand(BinaryOperator.ADD, value, null),
                        "right"),
                Arguments.of(
                        "StringFunctionOperand.function",
                        (Executable) () -> new StringFunctionOperand(null, STATUS),
                        "function"),
                Arguments.of(
                        "StringFunctionOperand.subject",
                        (Executable) () -> new StringFunctionOperand(StringFunction.LOWER, null),
                        "subject"),
                Arguments.of(
                        "SubstringOperand.subject", (Executable) () -> new SubstringOperand(null, 0, 1), "subject"),
                Arguments.of(
                        "AggregateProjection.function",
                        (Executable) () -> new AggregateProjection(null, STATUS),
                        "function"),
                Arguments.of(
                        "AggregateProjection.path",
                        (Executable) () -> new AggregateProjection(AggregateFunction.MAX, null),
                        "path"),
                Arguments.of(
                        "AggregateOperand.function", (Executable) () -> new AggregateOperand(null, STATUS), "function"),
                Arguments.of(
                        "AggregateOperand.path",
                        (Executable) () -> new AggregateOperand(AggregateFunction.MAX, null),
                        "path"),
                Arguments.of("Assignment.path", (Executable) () -> new Assignment(null, value), "path"),
                Arguments.of(
                        "UpdateSpec.entityType",
                        (Executable) () -> new UpdateSpec(null, List.of(new Assignment(STATUS, value)), null),
                        "entityType"),
                Arguments.of(
                        "UpdateSpec.assignments",
                        (Executable) () -> new UpdateSpec(String.class, null, null),
                        "assignments"),
                Arguments.of("DeleteSpec.entityType", (Executable) () -> new DeleteSpec(null, null), "entityType"),
                Arguments.of("PathProjection.path", (Executable) () -> new PathProjection(null), "path"),
                Arguments.of("ComputedProjection.operand", (Executable) () -> new ComputedProjection(null), "operand"),
                Arguments.of("Ordering.path", (Executable) () -> new Ordering(null, Ordering.Direction.ASC), "path"),
                Arguments.of("Ordering.direction", (Executable) () -> new Ordering(STATUS, null), "direction"));
    }

    @ParameterizedTest(name = "{0} 传 null")
    @MethodSource("requiredFields")
    @DisplayName("必填字段为 null：构造期即拒绝，消息点名是哪个字段")
    void requiredFieldRejectsNull(String field, Executable construction, String fieldName) {
        assertThatThrownBy(construction::execute)
                .as(field)
                .isInstanceOf(NullPointerException.class)
                .hasMessage(fieldName);
    }

    @Test
    @DisplayName("条件更新：没有赋值就拒绝——空 UPDATE 没有意义")
    void updateSpecRequiresAssignment() {
        assertThatThrownBy(() -> new UpdateSpec(String.class, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("set(...)");
    }

    @Test
    @DisplayName("条件更新：赋值列表是拷贝，where 可空（整表更新）")
    void updateSpecCopiesAssignments() {
        List<Assignment> assignments = new ArrayList<>(List.of(new Assignment(STATUS, "ARCHIVED")));

        UpdateSpec spec = new UpdateSpec(String.class, assignments, null);
        assignments.add(new Assignment(ID, "A"));

        assertThat(spec.assignments()).containsExactly(new Assignment(STATUS, "ARCHIVED"));
        assertThat(spec.where()).isNull();
        assertThatThrownBy(() -> spec.assignments().add(new Assignment(ID, "A")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("赋值允许 null 值：置 NULL 与「跳过这一列」是两件事")
    void assignmentAllowsNullValue() {
        Assignment assignment = new Assignment(STATUS, null);

        assertThat(assignment.value()).isNull();
        assertThat(assignment).isNotEqualTo(new Assignment(STATUS, "A"));
    }

    @Test
    @DisplayName("条件删除：where 可空（整表删除）")
    void deleteSpecAllowsUnconditional() {
        assertThat(new DeleteSpec(String.class, null).where()).isNull();
    }

    /// `Window` 的两个边界：`0` 合法（`limit = 0` 是「受限但不截断」），缺省用 `null`。
    @Test
    @DisplayName("窗口：0 是合法边界，缺省边界用 null 且不参与负数校验")
    void windowAcceptsZeroAndNullBounds() {
        Window zero = new Window(0, 0);

        assertThat(zero.offset()).isZero();
        assertThat(zero.limit()).isZero();
        assertThat(zero.limited()).isTrue();

        assertThat(new Window(null, null)).isEqualTo(Window.NONE);
        assertThat(new Window(3, null).limit()).isNull();
        assertThat(new Window(null, 3).offset()).isNull();
    }

    /// 每个节点的访问器都必须原样交回构造时的入参。
    ///
    /// record 的访问器是编译器生成的，但「交回哪个字段」由紧凑构造器里的赋值决定；
    /// 后端编译器与方言渲染全靠这些访问器取值，返回 null 会让整条链路在很远的地方崩。
    @Test
    @DisplayName("访问器原样交回入参")
    void accessorsReturnTheirComponents() {
        Logical or = new Logical(LogicalOperator.OR, List.of(new Constant(true)));
        Comparison comparison = new Comparison(STATUS, ComparisonOperator.GE, Literal.of("A"));
        Between between = new Between(CREATED_AT, Literal.of("a"), Literal.of("b"));
        In in = new In(STATUS, List.of("A"), false);
        ArithmeticOperand arithmetic = new ArithmeticOperand(BinaryOperator.MUL, Literal.of(2), Literal.of(3));
        AggregateProjection projection = new AggregateProjection(AggregateFunction.AVG, CREATED_AT);
        AggregateOperand aggregate = new AggregateOperand(AggregateFunction.SUM, ID);
        Ordering ordering = new Ordering(STATUS, Ordering.Direction.ASC);

        assertThat(new AttributePath(String.class, List.of("status")).entityType())
                .isEqualTo(String.class);
        assertThat(or.operator()).isEqualTo(LogicalOperator.OR);
        assertThat(or.operands()).hasSize(1);
        assertThat(comparison.left()).isEqualTo(STATUS);
        assertThat(comparison.operator()).isEqualTo(ComparisonOperator.GE);
        assertThat(comparison.right()).isEqualTo(Literal.of("A"));
        assertThat(between.subject()).isEqualTo(CREATED_AT);
        assertThat(between.lower()).isEqualTo(Literal.of("a"));
        assertThat(between.upper()).isEqualTo(Literal.of("b"));
        assertThat(in.path()).isEqualTo(STATUS);
        assertThat(in.negated()).isFalse();
        assertThat(new NullCheck(CREATED_AT, false).path()).isEqualTo(CREATED_AT);
        assertThat(arithmetic.operator()).isEqualTo(BinaryOperator.MUL);
        assertThat(arithmetic.left()).isEqualTo(Literal.of(2));
        assertThat(arithmetic.right()).isEqualTo(Literal.of(3));
        assertThat(projection.function()).isEqualTo(AggregateFunction.AVG);
        assertThat(projection.path()).isEqualTo(CREATED_AT);
        assertThat(aggregate.function()).isEqualTo(AggregateFunction.SUM);
        assertThat(aggregate.path()).isEqualTo(ID);
        assertThat(new PathProjection(CREATED_AT).path()).isEqualTo(CREATED_AT);
        assertThat(new ComputedProjection(Literal.of(1)).operand()).isEqualTo(Literal.of(1));
        assertThat(ordering.path()).isEqualTo(STATUS);
        assertThat(ordering.direction()).isEqualTo(Ordering.Direction.ASC);
        assertThat(Literal.of("A").value()).isEqualTo("A");
        assertThat(Constant.TRUE.value()).isTrue();
        assertThat(Constant.FALSE.value()).isFalse();
    }

    // ------------------------------------------- 字符串函数节点（3.1.0 新增操作数）

    @Test
    @DisplayName("字符串函数节点保留函数与主语，可互相嵌套")
    void stringFunctionOperandCarriesSubject() {
        StringFunctionOperand lower = new StringFunctionOperand(StringFunction.LOWER, STATUS);
        StringFunctionOperand nested = new StringFunctionOperand(StringFunction.TRIM, lower);

        assertThat(lower.function()).isEqualTo(StringFunction.LOWER);
        assertThat(lower.subject()).isEqualTo(STATUS);
        assertThat(nested.subject()).isEqualTo(lower);
    }

    @Test
    @DisplayName("截取节点保留位置与长度；length 为 null 表示截到末尾，长度为 0 合法，负数被拒绝")
    void substringOperandCarriesWindow() {
        SubstringOperand operand = new SubstringOperand(STATUS, 1, 3);

        assertThat(operand.subject()).isEqualTo(STATUS);
        assertThat(operand.offset()).isEqualTo(1);
        assertThat(operand.length()).isEqualTo(3);
        assertThat(new SubstringOperand(STATUS, 2, 0).length()).isZero();
        // 省略长度的形态与「截 0 个字符」「截一个上界数字个字符」都不是同一件事
        assertThat(new SubstringOperand(STATUS, 2, null).length()).isNull();
        assertThat(new SubstringOperand(STATUS, 2, null)).isNotEqualTo(new SubstringOperand(STATUS, 2, 0));

        assertThatThrownBy(() -> new SubstringOperand(STATUS, -1, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offset");
        assertThatThrownBy(() -> new SubstringOperand(STATUS, 0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length");
    }
}
