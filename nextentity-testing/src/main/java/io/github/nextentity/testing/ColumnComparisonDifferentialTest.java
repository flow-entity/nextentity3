package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/// 列对列比较矩阵的**差分** oracle：`EQ` / `NE` / `GT` / `GE` / `LT` / `LE` 六格各跑一条真库查询，
/// 期望 id 集合由内存实现按同一算子语义独立算出，两边逐行比对。
///
/// 差分分工（两条路径不相交）：
/// - 真库一侧：`Path.of(score).<算子>(Path.of(score2))`，由 JPA 渲染成 `score <op> score2` 并执行；
/// - 内存一侧：{@link Operator#matches} 直接读 {@link Fixtures#OPERATOR_ROWS} 的两列原始值自己比，
///   不经过 nextentity 的任何生产代码，也不读后端结果——否则就成了自己证明自己。
///
/// 夹具见 {@link Fixtures#OPERATOR_ROWS}，六个格子在它上面都有命中行与不命中行
/// （`lt` 的两行命中来自 id=5 的 `5 < 25` 与 id=6 的 `-4 < 3`），因此不存在「恒真 / 恒假」的假绿；
/// 每个格子的期望集合还会在用例内自检「非空且不等于全表」。
///
/// **三值逻辑**：任一侧为 `null` 时 SQL 比较结果是 `UNKNOWN`，`WHERE` 只保留 `TRUE`，
/// 所以 id=3（`score2` 为 `null`）与 id=4（`score` 为 `null`）六个格子全不命中；
/// 内存实现用同一规则（先判空再比），{@link #nullOperandRowsMatchNeitherEqNorNe()} 专门钉这一条。
///
/// @param <T> 后端实体类型
public abstract class ColumnComparisonDifferentialTest<T extends Item> extends ConsistencyTestBase<T> {

    @Override
    protected List<FixtureRow> fixtureRows() {
        return Fixtures.OPERATOR_ROWS;
    }

    /// 六格各一条用例：右侧是列（表达式），因此渲染走 `JpaPredicates.withExpression` 的对应分支。
    ///
    /// 用 `@TestFactory` 而不是 `@ParameterizedTest`：`junit-jupiter-params` 不在
    /// `nextentity-testing` 的编译类路径上（本模块只声明 `junit-jupiter-api`），而改 pom 是红线；
    /// 动态测试同样做到「一份用例体、六次参数化执行」，报表里每格仍是独立用例。
    ///
    /// @return 每个算子一条动态用例
    @TestFactory
    Stream<DynamicTest> everyOperatorMatchesMemoryOracle() {
        return Stream.of(Operator.values())
                .map(operator -> DynamicTest.dynamicTest(operator.name(), () -> assertOperator(operator)));
    }

    /// NULL 行专条：`=` 与 `<>` 都不命中两侧含 `NULL` 的行，因此两者并集不是全表。
    ///
    /// 这是 SQL 三值逻辑，不是缺陷：若某个后端把 `<>` 渲染成 `NOT (a = b)`，
    /// `NULL` 行就会被 `<>` 命中，本条当场变红。
    @Test
    void nullOperandRowsMatchNeitherEqNorNe() {
        Set<Long> nullRows = Fixtures.OPERATOR_ROWS.stream()
                .filter(row -> row.score() == null || row.score2() == null)
                .map(FixtureRow::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> equal =
                itemIds(query().where(scoreRef()).eq(Path.of(score2Ref())).list());
        Set<Long> different =
                itemIds(query().where(scoreRef()).ne(Path.of(score2Ref())).list());
        Set<Long> union = new LinkedHashSet<>(equal);
        union.addAll(different);

        assertThat(nullRows).isNotEmpty();
        assertThat(equal).doesNotContainAnyElementsOf(nullRows);
        assertThat(different).doesNotContainAnyElementsOf(nullRows);
        assertThat(union).isNotEqualTo(rowIds(Fixtures.OPERATOR_ROWS));
    }

    private void assertOperator(Operator operator) {
        List<T> rows =
                switch (operator) {
                    case EQ ->
                        query().where(scoreRef()).eq(Path.of(score2Ref())).list();
                    case NE ->
                        query().where(scoreRef()).ne(Path.of(score2Ref())).list();
                    case GT ->
                        query().where(scoreRef()).gt(Path.of(score2Ref())).list();
                    case GE ->
                        query().where(scoreRef()).ge(Path.of(score2Ref())).list();
                    case LT ->
                        query().where(scoreRef()).lt(Path.of(score2Ref())).list();
                    case LE ->
                        query().where(scoreRef()).le(Path.of(score2Ref())).list();
                };
        Set<Long> expected = operator.matchingIds(Fixtures.OPERATOR_ROWS);

        // oracle 自检：命中集合既非空、也不是全表，否则这条用例证明不了渲染对不对。
        assertThat(expected).isNotEmpty();
        assertThat(expected).isNotEqualTo(rowIds(Fixtures.OPERATOR_ROWS));
        assertThat(rows).extracting(Item::id).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static Set<Long> rowIds(List<FixtureRow> rows) {
        return rows.stream().map(FixtureRow::id).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<Long> itemIds(List<? extends Item> rows) {
        return rows.stream().map(Item::id).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /// 六个比较算子，以及它们在内存里的独立实现。
    enum Operator {
        EQ,
        NE,
        GT,
        GE,
        LT,
        LE;

        /// 内存语义：任一侧为 `null` 返回 `false`（SQL 里是 `UNKNOWN`，`WHERE` 不保留）。
        ///
        /// @param left  左列值或 `null`
        /// @param right 右列值或 `null`
        /// @return 该行是否命中
        boolean matches(Integer left, Integer right) {
            if (left == null || right == null) {
                return false;
            }
            return switch (this) {
                case EQ -> left.intValue() == right.intValue();
                case NE -> left.intValue() != right.intValue();
                case GT -> left > right;
                case GE -> left >= right;
                case LT -> left < right;
                case LE -> left <= right;
            };
        }

        /// 按内存语义算出期望 id 集合。
        ///
        /// @param rows 夹具原始行
        /// @return 命中的 id，按夹具顺序
        Set<Long> matchingIds(List<FixtureRow> rows) {
            return rows.stream()
                    .filter(row -> matches(row.score(), row.score2()))
                    .map(FixtureRow::id)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }
}
