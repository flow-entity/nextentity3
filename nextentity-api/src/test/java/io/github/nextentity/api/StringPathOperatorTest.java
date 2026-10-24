package io.github.nextentity.api;

import static io.github.nextentity.api.WireFormatFixture.CUSTOMER_NAME;
import static io.github.nextentity.api.WireFormatFixture.read;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.WireFormatFixture.Customer;
import io.github.nextentity.api.WireFormatFixture.Order;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/// `StringPath` 的线格式：`contains` / `like` 进 arguments 的是**原样**的入参，通配符由后端加。
///
/// 本套件钉的是 **`api` 自己的线格式契约**（`operation` / `arguments` / `subject` / `refs`）。
/// 「`CONTAINS` 渲染成 `%value%`、`LIKE` 原样透传」属于翻译层，证据在 `nextentity-core` 的
/// `ApiOperatorAssemblyTest`，两层不互相代替。
///
/// `*IfNotEmpty` / `*IfNotNull` 的两类短路要分开钉：`containsIfNotEmpty` 对 `null` 与空串都短路，
/// 而 `containsIfNotNull` / `likeIfNotNull` 只对 `null` 短路（空串仍是一条真条件）。
@DisplayName("StringPath：字符串算子与短路条件的线格式")
class StringPathOperatorTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("stringOperatorRows")
    @DisplayName("字符串算子：operation 与 arguments 精确相等，subject 为 null，refs 读到嵌套的 name")
    void stringOperator(
            String name, Operation expected, String argument, Function<StringPath<Order>, Predicate<Order>> call) {
        Op op = (Op) call.apply(customerName());

        assertThat(op.operation()).isEqualTo(expected);
        assertThat(op.arguments()).containsExactly(argument);
        assertThat(op.subject()).isNull();
        assertThat(op.refs()).hasSize(2);
        assertThat(read(op.refs())).isEqualTo(CUSTOMER_NAME);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shortCircuitCalls")
    @DisplayName("字符串短路：返回恒真占位（不实现 Op），且是同一个共享实例")
    void shortCircuit(String name, Function<StringPath<Order>, Predicate<Order>> call) {
        Predicate<Order> shortCircuited = call.apply(customerName());

        assertThat(shortCircuited).isNotInstanceOf(Op.class);
        assertThat(shortCircuited).isSameAs(Path.of(Order::getAge).eqIfNotNull(null));
    }

    private static StringPath<Order> customerName() {
        return EntityPath.of(Order::getCustomer).get(Customer::getName);
    }

    private static Stream<Arguments> stringOperatorRows() {
        return Stream.of(
                Arguments.of("contains", Operation.CONTAINS, "Ada", (Function<StringPath<Order>, Predicate<Order>>)
                        name -> name.contains("Ada")),
                Arguments.of("containsIfNotEmpty(非空)", Operation.CONTAINS, "Ada", (Function<
                                StringPath<Order>, Predicate<Order>>)
                        name -> name.containsIfNotEmpty("Ada")),
                Arguments.of("containsIfNotNull(非空)", Operation.CONTAINS, "Ada", (Function<
                                StringPath<Order>, Predicate<Order>>)
                        name -> name.containsIfNotNull("Ada")),
                Arguments.of("like", Operation.LIKE, "A%", (Function<StringPath<Order>, Predicate<Order>>)
                        name -> name.like("A%")),
                Arguments.of("likeIfNotNull(非空)", Operation.LIKE, "A%", (Function<StringPath<Order>, Predicate<Order>>)
                        name -> name.likeIfNotNull("A%")),
                Arguments.of("eqIfNotEmpty(非空)", Operation.EQ, "Ada", (Function<StringPath<Order>, Predicate<Order>>)
                        name -> name.eqIfNotEmpty("Ada")));
    }

    private static Stream<Arguments> shortCircuitCalls() {
        return Stream.of(
                Arguments.of("containsIfNotEmpty(null)", (Function<StringPath<Order>, Predicate<Order>>)
                        name -> name.containsIfNotEmpty(null)),
                Arguments.of("containsIfNotEmpty(空串)", (Function<StringPath<Order>, Predicate<Order>>)
                        name -> name.containsIfNotEmpty("")),
                Arguments.of("containsIfNotNull(null)", (Function<StringPath<Order>, Predicate<Order>>)
                        name -> name.containsIfNotNull(null)),
                Arguments.of("likeIfNotNull(null)", (Function<StringPath<Order>, Predicate<Order>>)
                        name -> name.likeIfNotNull(null)),
                Arguments.of("eqIfNotEmpty(null)", (Function<StringPath<Order>, Predicate<Order>>)
                        name -> name.eqIfNotEmpty(null)),
                Arguments.of("eqIfNotEmpty(空串)", (Function<StringPath<Order>, Predicate<Order>>)
                        name -> name.eqIfNotEmpty("")));
    }
}
