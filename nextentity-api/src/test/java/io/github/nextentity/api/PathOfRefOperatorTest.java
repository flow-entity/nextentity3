package io.github.nextentity.api;

import static io.github.nextentity.api.WireFormatFixture.AGE;
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

/// `Path.of(PathRef)` 的线格式：比较 / 区间 / 空值 / 集合算子，以及四个聚合。
///
/// 本套件钉的是 **`api` 自己的线格式契约**——`Op#operation()` / `Op#arguments()` / `Op#subject()` /
/// `Op#refs()` 这组读取方法的精确内容。把同一个 `Op` 翻译成 `core.spec` 节点是后端的职责，那份证据在
/// `nextentity-core` 的 `ApiOperatorAssemblyTest`；两层断言互不代替：那边证明「翻译对」，这边证明
/// 「值本身带够了信息」，而且不依赖任何 `core` 类型。
///
/// `refs()` 一律用 `apply(实体)` 断言取到的字段值：链长、链上有几段都不如「这一段的 lambda 真的读到了
/// 这个字段」有说服力。
@DisplayName("Path.of(PathRef)：算子与聚合的线格式")
class PathOfRefOperatorTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("valueOperators")
    @DisplayName("算子：operation 与 arguments 精确相等，subject 为 null，refs 读到 age")
    void valueOperator(
            String name,
            Operation expected,
            List<?> expectedArguments,
            Function<Path<Order, Integer>, Predicate<Order>> call) {
        Op op = (Op) call.apply(Path.of(Order::getAge));

        assertThat(op.operation()).isEqualTo(expected);
        assertThat(op.arguments()).isEqualTo(expectedArguments);
        assertThat(op.subject()).isNull();
        assertThat(op.refs()).hasSize(1);
        assertThat(read(op.refs())).isEqualTo(AGE);
    }

    @Test
    @DisplayName("notBetween：先成 BETWEEN 叶子再包一层 NOT，两层 refs 都指向同一段路径")
    void notBetweenWrapsBetween() {
        Op negation = (Op) Path.of(Order::getAge).notBetween(3, 5);

        assertThat(negation.operation()).isEqualTo(Operation.NOT);
        assertThat(negation.refs()).isEmpty();
        assertThat(negation.subject()).isNull();
        assertThat(negation.arguments()).singleElement().isInstanceOfSatisfying(Op.class, between -> {
            assertThat(between.operation()).isEqualTo(Operation.BETWEEN);
            assertThat(between.arguments()).containsExactly(3, 5);
            assertThat(between.subject()).isNull();
            assertThat(read(between.refs())).isEqualTo(AGE);
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("aggregates")
    @DisplayName("四个聚合：arguments 为空、subject 为 null，被聚合的路径走 refs")
    void aggregate(String name, Operation expected, Function<Path<Order, Integer>, AggregateValue<Order, ?>> call) {
        Op op = (Op) call.apply(Path.of(Order::getAge));

        assertThat(op.operation()).isEqualTo(expected);
        assertThat(op.arguments()).isEmpty();
        assertThat(op.subject()).isNull();
        assertThat(op.refs()).hasSize(1);
        assertThat(read(op.refs())).isEqualTo(AGE);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nullShortCircuitCalls")
    @DisplayName("*IfNotNull(null)：返回恒真占位（不实现 Op），且是同一个共享实例")
    void nullShortCircuit(String name, Function<Path<Order, Integer>, Predicate<Order>> call) {
        Predicate<Order> shortCircuited = call.apply(Path.of(Order::getAge));

        assertThat(shortCircuited).isNotInstanceOf(Op.class);
        assertThat(shortCircuited).isSameAs(Path.of(Order::getAge).eqIfNotNull(null));
    }

    private static Stream<Arguments> valueOperators() {
        Path<Order, Integer> score = Path.of(Order::getScore);
        Path<Order, Integer> level = Path.of(Order::getLevel);
        return Stream.of(
                row("eq(值)", Operation.EQ, List.of(3), path -> path.eq(3)),
                row("eq(表达式)", Operation.EQ, List.of(score), path -> path.eq(score)),
                row("eqIfNotNull(值)", Operation.EQ, List.of(3), path -> path.eqIfNotNull(3)),
                row("ne(值)", Operation.NE, List.of(3), path -> path.ne(3)),
                row("ne(表达式)", Operation.NE, List.of(score), path -> path.ne(score)),
                row("ge(值)", Operation.GE, List.of(3), path -> path.ge(3)),
                row("ge(表达式)", Operation.GE, List.of(score), path -> path.ge(score)),
                row("gt(值)", Operation.GT, List.of(3), path -> path.gt(3)),
                row("gt(表达式)", Operation.GT, List.of(score), path -> path.gt(score)),
                row("le(值)", Operation.LE, List.of(3), path -> path.le(3)),
                row("le(表达式)", Operation.LE, List.of(score), path -> path.le(score)),
                row("lt(值)", Operation.LT, List.of(3), path -> path.lt(3)),
                row("lt(表达式)", Operation.LT, List.of(score), path -> path.lt(score)),
                row("geIfNotNull(值)", Operation.GE, List.of(3), path -> path.geIfNotNull(3)),
                row("gtIfNotNull(值)", Operation.GT, List.of(3), path -> path.gtIfNotNull(3)),
                row("leIfNotNull(值)", Operation.LE, List.of(3), path -> path.leIfNotNull(3)),
                row("ltIfNotNull(值)", Operation.LT, List.of(3), path -> path.ltIfNotNull(3)),
                row("isNull", Operation.IS_NULL, List.of(), Path::isNull),
                row("isNotNull", Operation.IS_NOT_NULL, List.of(), Path::isNotNull),
                row("in(集合)", Operation.IN, List.of(List.of(3, 5)), path -> path.in(List.of(3, 5))),
                row("in(变长)", Operation.IN, List.of(List.of(3, 5)), path -> path.in(3, 5)),
                row("notIn(集合)", Operation.NOT_IN, List.of(List.of(3, 5)), path -> path.notIn(List.of(3, 5))),
                row("notIn(变长)", Operation.NOT_IN, List.of(List.of(3, 5)), path -> path.notIn(3, 5)),
                row("inIfNotNull(集合)", Operation.IN, List.of(List.of(7)), path -> path.inIfNotNull(List.of(7))),
                row("between(值)", Operation.BETWEEN, List.of(3, 5), path -> path.between(3, 5)),
                row("between(表达式)", Operation.BETWEEN, List.of(score, level), path -> path.between(score, level)));
    }

    private static Stream<Arguments> aggregates() {
        return Stream.of(
                Arguments.of("count", Operation.COUNT, (Function<Path<Order, Integer>, AggregateValue<Order, ?>>)
                        Path::count),
                Arguments.of("countDistinct", Operation.COUNT_DISTINCT, (Function<
                                Path<Order, Integer>, AggregateValue<Order, ?>>)
                        Path::countDistinct),
                Arguments.of(
                        "max", Operation.MAX, (Function<Path<Order, Integer>, AggregateValue<Order, ?>>) Path::max),
                Arguments.of(
                        "min", Operation.MIN, (Function<Path<Order, Integer>, AggregateValue<Order, ?>>) Path::min));
    }

    private static Stream<Arguments> nullShortCircuitCalls() {
        return Stream.of(
                Arguments.of("eqIfNotNull(null)", (Function<Path<Order, Integer>, Predicate<Order>>)
                        path -> path.eqIfNotNull(null)),
                Arguments.of("geIfNotNull(null)", (Function<Path<Order, Integer>, Predicate<Order>>)
                        path -> path.geIfNotNull(null)),
                Arguments.of("gtIfNotNull(null)", (Function<Path<Order, Integer>, Predicate<Order>>)
                        path -> path.gtIfNotNull(null)),
                Arguments.of("leIfNotNull(null)", (Function<Path<Order, Integer>, Predicate<Order>>)
                        path -> path.leIfNotNull(null)),
                Arguments.of("ltIfNotNull(null)", (Function<Path<Order, Integer>, Predicate<Order>>)
                        path -> path.ltIfNotNull(null)),
                Arguments.of("inIfNotNull(null)", (Function<Path<Order, Integer>, Predicate<Order>>)
                        path -> path.inIfNotNull(null)));
    }

    private static Arguments row(
            String name,
            Operation expected,
            List<?> expectedArguments,
            Function<Path<Order, Integer>, Predicate<Order>> call) {
        return Arguments.of(name, expected, expectedArguments, call);
    }
}
