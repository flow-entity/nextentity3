package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/// 表达式算术矩阵的**差分** oracle：`add` / `sub` / `mul` / `div` / `mod` 五格各跑一条
/// 「算术表达式进投影」的查询，期望值由内存实现按算子语义独立算出，逐值比对。
///
/// 为什么用投影而不是只看行集：投影把**每一行算出来的值**都比一遍。
/// 只看命中 id 的话，`sub` 写成 `5 - score`（交换操作数）、`div` 写成四舍五入这类错误
/// 仍可能凑出同一批 id；逐值比对时它们立刻露出来。
///
/// 差分分工（两条路径不相交）：
/// - 真库一侧：`select(Path.of(score).<算子>(n)).orderBy(id).asc()`，渲染走 `JpaOperands` 的算术分支；
/// - 内存一侧：{@link Operator#apply} 直接读 {@link Fixtures#OPERATOR_ROWS} 的 `score` 原始值自己算，
///   不经过 nextentity 的任何生产代码，也不读后端结果。
///
/// 夹具见 {@link Fixtures#OPERATOR_ROWS}（`score` = 10 / 20 / 30 / `null` / 5 / -4，按 id 升序），
/// 查询同样按 id 升序取，因此结果与夹具行一一对应。id=4 的 `score` 是 `null`，
/// 它钉的是 **NULL 传播**：任一侧为 `null` 时算术结果是 `null`，对应比较为 `UNKNOWN`。
///
/// **整数除法 / 取模的后端实测**（H2 `MODE=MySQL` + Hibernate 7.1.8，渲染成 `(score/3)` / `(score%3)`）：
/// ```text
/// score / 3 → 3, 6, 10, null, 1, -1    INTEGER / INTEGER 向零截断，结果类型仍是 Integer
/// score % 3 → 1, 2, 0,  null, 2, -1    余数符号跟随被除数（-4 % 3 = -1）
/// score / 0、score % 0 → org.hibernate.exception.DataException（H2 报 "Division by zero"）
/// ```
/// 因此内存一侧直接用 Java 的 `/` 与 `%`：两者都是向零截断、余数跟随被除数，与上面实测逐值一致。
///
/// 除数为 0 **不进矩阵**：H2 抛异常，而 MySQL 的 `/ 0` 返回 `NULL`，两个后端行为不同；
/// 把任一侧写进后端中立的一致性基类都会绑死方言，这件事属于 L4（同一条用例跨方言对拍）的职责。
///
/// @param <T> 后端实体类型
public abstract class ArithmeticDifferentialTest<T extends Item> extends ConsistencyTestBase<T> {

    @Override
    protected List<FixtureRow> fixtureRows() {
        return Fixtures.OPERATOR_ROWS;
    }

    /// 五格各一条用例：算术表达式进投影，逐值比对真库结果与内存结果。
    ///
    /// 用 `@TestFactory` 而不是 `@ParameterizedTest`：`junit-jupiter-params` 不在
    /// `nextentity-testing` 的编译类路径上（本模块只声明 `junit-jupiter-api`），而改 pom 是红线；
    /// 动态测试同样做到「一份用例体、五次参数化执行」，报表里每格仍是独立用例。
    ///
    /// @return 每个算子一条动态用例
    @TestFactory
    Stream<DynamicTest> everyOperatorMatchesMemoryOracle() {
        return Stream.of(Operator.values())
                .map(operator -> DynamicTest.dynamicTest(operator.name(), () -> assertOperator(operator)));
    }

    private void assertOperator(Operator operator) {
        List<Integer> actual =
                switch (operator) {
                    case ADD ->
                        query().select(Path.of(scoreRef()).add(operator.operand()))
                                .orderBy(idRef())
                                .asc()
                                .list();
                    case SUB ->
                        query().select(Path.of(scoreRef()).sub(operator.operand()))
                                .orderBy(idRef())
                                .asc()
                                .list();
                    case MUL ->
                        query().select(Path.of(scoreRef()).mul(operator.operand()))
                                .orderBy(idRef())
                                .asc()
                                .list();
                    case DIV ->
                        query().select(Path.of(scoreRef()).div(operator.operand()))
                                .orderBy(idRef())
                                .asc()
                                .list();
                    case MOD ->
                        query().select(Path.of(scoreRef()).mod(operator.operand()))
                                .orderBy(idRef())
                                .asc()
                                .list();
                };
        List<Integer> expected = operator.expectedScores(Fixtures.OPERATOR_ROWS);

        // oracle 自检：期望值必须跨多行不同，否则「算子写错」也可能碰巧对上。
        assertThat(expected.stream().filter(Objects::nonNull).distinct()).hasSizeGreaterThan(1);
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    /// 五个算术算子：参与运算的字面量，以及它们在内存里的独立实现。
    ///
    /// 字面量按「能切开写错的算子」挑：`sub` / `mul` 用 5（负数行 `-4` 使交换操作数后结果不同），
    /// `div` / `mod` 用 3（`10 / 3`、`-4 / 3`、`-4 % 3` 同时钉住截断方向与余数符号）。
    enum Operator {
        ADD(5),
        SUB(5),
        MUL(5),
        DIV(3),
        MOD(3);

        private final int operand;

        Operator(int operand) {
            this.operand = operand;
        }

        /// 参与运算的字面量。
        ///
        /// @return 右操作数
        int operand() {
            return operand;
        }

        /// 内存语义：`null` 参与运算得到 `null`（NULL 传播），与 SQL 一致。
        ///
        /// `/` 与 `%` 用 Java 语义：向零截断、余数跟随被除数，与实测的后端行为相同。
        ///
        /// @param value 列值或 `null`
        /// @return 该行算出来的值或 `null`
        Integer apply(Integer value) {
            if (value == null) {
                return null;
            }
            return switch (this) {
                case ADD -> value + operand;
                case SUB -> value - operand;
                case MUL -> value * operand;
                case DIV -> value / operand;
                case MOD -> value % operand;
            };
        }

        /// 按内存语义算出期望的投影列。
        ///
        /// @param rows 夹具原始行，按 id 升序
        /// @return 与夹具行一一对应的期望值
        List<Integer> expectedScores(List<FixtureRow> rows) {
            return rows.stream().map(row -> apply(row.score())).toList();
        }
    }
}
