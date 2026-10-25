package io.github.nextentity.api;

import static io.github.nextentity.api.WireFormatFixture.SALARY;
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

/// 聚合值与算术值参与比较/区间时的线格式：`subject()` 必须指向左侧主体。
///
/// 本套件钉的是 **`api` 自己的线格式契约**。`subject` 携带的是「左侧不是属性路径」这件事：
/// `max(salary) > 100` 与 `salary > 100` 的 `refs()` 完全相同，后端只能靠 `subject()` 区分两者；
/// 翻译成 `AggregateOperand` / `ArithmeticOperand` 的证据在 `nextentity-core` 的
/// `ApiOperatorAssemblyTest`，两层不互相代替。
@DisplayName("AggregateValue / NumberValue：比较与区间的 subject 线格式")
class AggregateValueOperatorTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("aggregateOperatorRows")
    @DisplayName("聚合值：subject 指向聚合值本身，arguments 是右操作数")
    void aggregateOperator(
            String name,
            Operation expected,
            List<?> expectedArguments,
            Function<AggregateValue<Order, Double>, Predicate<Order>> call) {
        AggregateValue<Order, Double> max = Path.of(Order::getSalary).max();
        Op leaf = (Op) call.apply(max);

        assertThat(leaf.operation()).isEqualTo(expected);
        assertThat(leaf.arguments()).isEqualTo(expectedArguments);
        assertThat(leaf.subject()).isSameAs(max);
        assertThat(read(leaf.refs())).isEqualTo(SALARY);
    }

    @Test
    @DisplayName("聚合值：eqIfNotNull(null) 短路成恒真占位，不产生带 subject 的叶子")
    void aggregateNullShortCircuit() {
        AggregateValue<Order, Double> max = Path.of(Order::getSalary).max();

        Predicate<Order> shortCircuited = max.eqIfNotNull(null);
        assertThat(shortCircuited).isNotInstanceOf(Op.class);
        assertThat(shortCircuited).isInstanceOf(SkippedCondition.class);
        assertThat(shortCircuited).isSameAs(Path.of(Order::getAge).eqIfNotNull(null));
    }

    @Test
    @DisplayName("聚合值 notBetween：NOT 里包一层带 subject 的 BETWEEN")
    void aggregateNotBetween() {
        AggregateValue<Order, Double> max = Path.of(Order::getSalary).max();
        Op negation = (Op) max.notBetween(100.0, 200.0);

        assertThat(negation.operation()).isEqualTo(Operation.NOT);
        assertThat(negation.refs()).isEmpty();
        assertThat(negation.subject()).isNull();
        assertThat(negation.arguments()).singleElement().isInstanceOfSatisfying(Op.class, between -> {
            assertThat(between.operation()).isEqualTo(Operation.BETWEEN);
            assertThat(between.arguments()).containsExactly(100.0, 200.0);
            assertThat(between.subject()).isSameAs(max);
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("arithmeticOperatorRows")
    @DisplayName("算术值：subject 指向算术值本身，arguments 是右操作数")
    void arithmeticOperator(
            String name,
            Operation expected,
            List<?> expectedArguments,
            Function<NumberValue<Order, Double>, Predicate<Order>> call) {
        NumberValue<Order, Double> plus = Path.of(Order::getSalary).add(1000.0);
        Op leaf = (Op) call.apply(plus);

        assertThat(leaf.operation()).isEqualTo(expected);
        assertThat(leaf.arguments()).isEqualTo(expectedArguments);
        assertThat(leaf.subject()).isSameAs(plus);
        assertThat(read(leaf.refs())).isEqualTo(SALARY);
    }

    @Test
    @DisplayName("算术值：eqIfNotNull(null) 短路成恒真占位，notBetween 包一层 NOT")
    void arithmeticNullShortCircuitAndNotBetween() {
        NumberValue<Order, Double> plus = Path.of(Order::getSalary).add(1000.0);

        Predicate<Order> shortCircuited = plus.eqIfNotNull(null);
        assertThat(shortCircuited).isNotInstanceOf(Op.class);
        assertThat(shortCircuited).isInstanceOf(SkippedCondition.class);
        assertThat(shortCircuited).isSameAs(Path.of(Order::getAge).eqIfNotNull(null));

        Op negation = (Op) plus.notBetween(1000.0, 2000.0);
        assertThat(negation.operation()).isEqualTo(Operation.NOT);
        assertThat(negation.refs()).isEmpty();
        assertThat(negation.arguments()).singleElement().isInstanceOfSatisfying(Op.class, between -> {
            assertThat(between.operation()).isEqualTo(Operation.BETWEEN);
            assertThat(between.arguments()).containsExactly(1000.0, 2000.0);
            assertThat(between.subject()).isSameAs(plus);
        });
    }

    private static Stream<Arguments> aggregateOperatorRows() {
        Path<Order, Double> bonus = Path.of(Order::getBonus);
        Path<Order, Double> baseSalary = Path.of(Order::getBaseSalary);
        return Stream.of(
                aggregateRow("eq", Operation.EQ, List.of(100.0), aggregate -> aggregate.eq(100.0)),
                aggregateRow("eqIfNotNull", Operation.EQ, List.of(100.0), aggregate -> aggregate.eqIfNotNull(100.0)),
                aggregateRow("ne", Operation.NE, List.of(100.0), aggregate -> aggregate.ne(100.0)),
                aggregateRow("ge", Operation.GE, List.of(100.0), aggregate -> aggregate.ge(100.0)),
                aggregateRow("gt", Operation.GT, List.of(100.0), aggregate -> aggregate.gt(100.0)),
                aggregateRow("le", Operation.LE, List.of(100.0), aggregate -> aggregate.le(100.0)),
                aggregateRow("lt", Operation.LT, List.of(100.0), aggregate -> aggregate.lt(100.0)),
                aggregateRow(
                        "between(值)",
                        Operation.BETWEEN,
                        List.of(100.0, 200.0),
                        aggregate -> aggregate.between(100.0, 200.0)),
                aggregateRow(
                        "between(表达式)",
                        Operation.BETWEEN,
                        List.of(bonus, baseSalary),
                        aggregate -> aggregate.between(bonus, baseSalary)));
    }

    private static Stream<Arguments> arithmeticOperatorRows() {
        Path<Order, Double> bonus = Path.of(Order::getBonus);
        Path<Order, Double> baseSalary = Path.of(Order::getBaseSalary);
        return Stream.of(
                arithmeticRow("eq", Operation.EQ, List.of(5000.0), value -> value.eq(5000.0)),
                arithmeticRow("eqIfNotNull", Operation.EQ, List.of(5000.0), value -> value.eqIfNotNull(5000.0)),
                arithmeticRow("ne", Operation.NE, List.of(5000.0), value -> value.ne(5000.0)),
                arithmeticRow("ge", Operation.GE, List.of(5000.0), value -> value.ge(5000.0)),
                arithmeticRow("gt", Operation.GT, List.of(5000.0), value -> value.gt(5000.0)),
                arithmeticRow("le", Operation.LE, List.of(5000.0), value -> value.le(5000.0)),
                arithmeticRow("lt", Operation.LT, List.of(5000.0), value -> value.lt(5000.0)),
                arithmeticRow(
                        "between(值)",
                        Operation.BETWEEN,
                        List.of(1000.0, 2000.0),
                        value -> value.between(1000.0, 2000.0)),
                arithmeticRow(
                        "between(表达式)",
                        Operation.BETWEEN,
                        List.of(bonus, baseSalary),
                        value -> value.between(bonus, baseSalary)));
    }

    private static Arguments aggregateRow(
            String name,
            Operation expected,
            List<?> expectedArguments,
            Function<AggregateValue<Order, Double>, Predicate<Order>> call) {
        return Arguments.of(name, expected, expectedArguments, call);
    }

    private static Arguments arithmeticRow(
            String name,
            Operation expected,
            List<?> expectedArguments,
            Function<NumberValue<Order, Double>, Predicate<Order>> call) {
        return Arguments.of(name, expected, expectedArguments, call);
    }
}
