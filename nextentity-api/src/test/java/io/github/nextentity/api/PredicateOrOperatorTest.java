package io.github.nextentity.api;

import static io.github.nextentity.api.WireFormatFixture.SCORE;
import static io.github.nextentity.api.WireFormatFixture.read;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.WireFormatFixture.Order;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/// `predicate.or(PathRef)` 的线格式：`PathOperator` 的全部算子，以及「可选条件拼接」的短路语义。
///
/// 本套件钉的是 **`api` 自己的线格式契约**（`operation` / `arguments` / `subject` / `refs`）。
/// `PathOperator` 的翻译证据在 `nextentity-core` 的 `ApiOperatorAssemblyTest`，两层不互相代替。
///
/// 这里两类形状必须分清：
/// - 算子成功拼上：结果是 `Leaf(OR, 空 refs, [parent, next])`——`refs()` 为空是**故意的**，
///   两段路径分别住在两个子叶子里；后半条不带 `parent` 就会被后端读成「只有一个条件」。
/// - `*IfNotNull(null)`：**只丢本次条件**，返回的就是 `parent` 本身（同一个实例）。若返回恒真占位，
///   `x.eq(3).or(A::getScore).eqIfNotNull(null)` 会从「`x = 3`」退化成「无条件」，即返回全表。
///   已恒真的链（`TRUE ∨ x`）是唯一的例外：短路后仍然恒真。
@DisplayName("predicate.or(PathRef)：可选条件拼接的线格式")
class PredicateOrOperatorTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("orOperatorRows")
    @DisplayName("算子：包成 Leaf(OR, 空 refs, [parent, next])，arguments 恰好是这两条")
    void orOperator(
            String name,
            Operation expected,
            List<?> expectedArguments,
            Function<PathOperator<Order, Integer, Predicate<Order>>, Predicate<Order>> call) {
        Predicate<Order> parent = parent();
        Op outer = (Op) call.apply(parent.or(Order::getScore));

        assertThat(outer.operation()).isEqualTo(Operation.OR);
        assertThat(outer.refs()).isEmpty();
        assertThat(outer.subject()).isNull();
        assertThat(outer.arguments()).hasSize(2);
        assertThat(outer.arguments().get(0)).isSameAs(parent);

        Op next = (Op) outer.arguments().get(1);
        assertThat(outer.arguments()).containsExactly(parent, next);
        assertThat(next.operation()).isEqualTo(expected);
        assertThat(next.arguments()).isEqualTo(expectedArguments);
        assertThat(next.subject()).isNull();
        assertThat(next.refs()).hasSize(1);
        assertThat(read(next.refs())).isEqualTo(SCORE);
    }

    @Test
    @DisplayName("notBetween：先组合成 Leaf(OR, …)，再在外面包一层 NOT")
    void notBetweenWrapsCombination() {
        Predicate<Order> parent = parent();
        Op negation = (Op) parent.or(Order::getScore).notBetween(5, 6);

        assertThat(negation.operation()).isEqualTo(Operation.NOT);
        assertThat(negation.refs()).isEmpty();
        assertThat(negation.subject()).isNull();
        assertThat(negation.arguments()).singleElement().isInstanceOfSatisfying(Op.class, combination -> {
            assertThat(combination.operation()).isEqualTo(Operation.OR);
            assertThat(combination.refs()).isEmpty();
            assertThat(combination.arguments()).hasSize(2);
            assertThat(combination.arguments().get(0)).isSameAs(parent);

            Op between = (Op) combination.arguments().get(1);
            assertThat(combination.arguments()).containsExactly(parent, between);
            assertThat(between.operation()).isEqualTo(Operation.BETWEEN);
            assertThat(between.arguments()).containsExactly(5, 6);
            assertThat(read(between.refs())).isEqualTo(SCORE);
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("skipCalls")
    @DisplayName("非恒真链的短路：*IfNotNull(null) 返回 parent 本身，前半条一个节点都没丢")
    void skipReturnsParent(
            String name, Function<PathOperator<Order, Integer, Predicate<Order>>, Predicate<Order>> call) {
        Predicate<Order> parent = parent();

        assertThat(call.apply(parent.or(Order::getScore))).isSameAs(parent);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("skipCalls")
    @DisplayName("恒真链的短路对照：整条链已恒真，短路后仍是同一个恒真占位（不实现 Op）")
    void skipOnSuppressedChainStaysAlwaysTrue(
            String name, Function<PathOperator<Order, Integer, Predicate<Order>>, Predicate<Order>> call) {
        Predicate<Order> alwaysTrue = Path.of(Order::getAge).eqIfNotNull(null);
        Predicate<Order> shortCircuited = call.apply(alwaysTrue.or(Order::getScore));

        assertThat(shortCircuited).isNotInstanceOf(Op.class);
        assertThat(shortCircuited).isSameAs(Path.of(Order::getAge).ltIfNotNull(null));
    }

    @Test
    @DisplayName("恒真链上拼非 null 条件：TRUE ∨ x 仍是恒真，不为它建任何节点")
    void suppressedChainIgnoresAppendedCondition() {
        Predicate<Order> alwaysTrue = Path.of(Order::getAge).eqIfNotNull(null);

        assertThat(alwaysTrue.or(Order::getScore).eq(5)).isSameAs(alwaysTrue);
        assertThat(alwaysTrue.or(Order::getScore).in(List.of(5, 6))).isSameAs(alwaysTrue);
        assertThat(alwaysTrue.or(Order::getScore).eqIfNotNull(5)).isSameAs(alwaysTrue);
    }

    private static Predicate<Order> parent() {
        return Path.of(Order::getAge).eq(3);
    }

    private static Stream<Arguments> orOperatorRows() {
        Path<Order, Integer> age = Path.of(Order::getAge);
        Path<Order, Integer> level = Path.of(Order::getLevel);
        return Stream.of(
                row("eq(值)", Operation.EQ, List.of(5), operator -> operator.eq(5)),
                row("eq(表达式)", Operation.EQ, List.of(age), operator -> operator.eq(age)),
                row("eqIfNotNull(值)", Operation.EQ, List.of(5), operator -> operator.eqIfNotNull(5)),
                row("ne(值)", Operation.NE, List.of(5), operator -> operator.ne(5)),
                row("ne(表达式)", Operation.NE, List.of(age), operator -> operator.ne(age)),
                row("ge(值)", Operation.GE, List.of(5), operator -> operator.ge(5)),
                row("ge(表达式)", Operation.GE, List.of(age), operator -> operator.ge(age)),
                row("gt(值)", Operation.GT, List.of(5), operator -> operator.gt(5)),
                row("gt(表达式)", Operation.GT, List.of(age), operator -> operator.gt(age)),
                row("le(值)", Operation.LE, List.of(5), operator -> operator.le(5)),
                row("le(表达式)", Operation.LE, List.of(age), operator -> operator.le(age)),
                row("lt(值)", Operation.LT, List.of(5), operator -> operator.lt(5)),
                row("lt(表达式)", Operation.LT, List.of(age), operator -> operator.lt(age)),
                row("geIfNotNull(值)", Operation.GE, List.of(5), operator -> operator.geIfNotNull(5)),
                row("gtIfNotNull(值)", Operation.GT, List.of(5), operator -> operator.gtIfNotNull(5)),
                row("leIfNotNull(值)", Operation.LE, List.of(5), operator -> operator.leIfNotNull(5)),
                row("ltIfNotNull(值)", Operation.LT, List.of(5), operator -> operator.ltIfNotNull(5)),
                row("isNull", Operation.IS_NULL, List.of(), PathOperator::isNull),
                row("isNotNull", Operation.IS_NOT_NULL, List.of(), PathOperator::isNotNull),
                row("in(集合)", Operation.IN, List.of(List.of(5, 6)), operator -> operator.in(List.of(5, 6))),
                row("in(变长)", Operation.IN, List.of(List.of(5, 6)), operator -> operator.in(5, 6)),
                row("notIn(集合)", Operation.NOT_IN, List.of(List.of(5, 6)), operator -> operator.notIn(List.of(5, 6))),
                row("notIn(变长)", Operation.NOT_IN, List.of(List.of(5, 6)), operator -> operator.notIn(5, 6)),
                row("inIfNotNull(集合)", Operation.IN, List.of(List.of(5)), operator -> operator.inIfNotNull(List.of(5))),
                row("between(值)", Operation.BETWEEN, List.of(5, 6), operator -> operator.between(5, 6)),
                row("between(表达式)", Operation.BETWEEN, List.of(age, level), operator -> operator.between(age, level)));
    }

    private static Stream<Arguments> skipCalls() {
        return Stream.of(
                skipRow("eqIfNotNull(null)", operator -> operator.eqIfNotNull(null)),
                skipRow("geIfNotNull(null)", operator -> operator.geIfNotNull(null)),
                skipRow("gtIfNotNull(null)", operator -> operator.gtIfNotNull(null)),
                skipRow("leIfNotNull(null)", operator -> operator.leIfNotNull(null)),
                skipRow("ltIfNotNull(null)", operator -> operator.ltIfNotNull(null)),
                skipRow("inIfNotNull(null)", operator -> operator.inIfNotNull(null)));
    }

    private static Arguments row(
            String name,
            Operation expected,
            List<?> expectedArguments,
            Function<PathOperator<Order, Integer, Predicate<Order>>, Predicate<Order>> call) {
        return Arguments.of(name, expected, expectedArguments, call);
    }

    private static Arguments skipRow(
            String name, Function<PathOperator<Order, Integer, Predicate<Order>>, Predicate<Order>> call) {
        return Arguments.of(name, call);
    }
}
