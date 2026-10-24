package io.github.nextentity.api;

import static io.github.nextentity.api.WireFormatFixture.SALARY;
import static io.github.nextentity.api.WireFormatFixture.read;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.WireFormatFixture.Order;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/// `Path.of(NumberRef)` 的线格式：`sum()` / `avg()` 两个聚合，以及五个算术算子与算术链。
///
/// 本套件钉的是 **`api` 自己的线格式契约**（`operation` / `arguments` / `subject` / `refs`）。
/// 把 `Op` 翻译成 `core.spec` 节点是后端的职责，证据在 `nextentity-core` 的 `ApiOperatorAssemblyTest`，
/// 两层不互相代替。
///
/// 算术的线格式有个容易漏掉的区别：`Path.of(...).add(x)` 的 `subject()` 是 `null`（左操作数就是
/// `refs()` 的路径），而 `add(x).mul(y)` 的 `subject()` 指向内层算术值——这是后端区分 `(a + x) * y`
/// 与 `a * y` 的唯一依据。
@DisplayName("Path.of(NumberRef)：sum/avg 与算术链的线格式")
class NumberPathOperatorTest {

    @Test
    @DisplayName("sum/avg：两个聚合，arguments 为空、subject 为 null，被聚合的路径走 refs")
    void sumAndAvg() {
        NumberPath<Order, Double> salary = Path.of(Order::getSalary);

        Op sum = (Op) salary.sum();
        assertThat(sum.operation()).isEqualTo(Operation.SUM);
        assertThat(sum.arguments()).isEmpty();
        assertThat(sum.subject()).isNull();
        assertThat(read(sum.refs())).isEqualTo(SALARY);

        Op avg = (Op) salary.avg();
        assertThat(avg.operation()).isEqualTo(Operation.AVG);
        assertThat(avg.arguments()).isEmpty();
        assertThat(avg.subject()).isNull();
        assertThat(read(avg.refs())).isEqualTo(SALARY);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("arithmeticRows")
    @DisplayName("算术算子：右操作数进 arguments，subject 为 null（左操作数就是 refs 的路径）")
    void arithmetic(
            String name,
            Operation expected,
            Double right,
            Function<NumberPath<Order, Double>, NumberValue<Order, Double>> call) {
        Op op = (Op) call.apply(Path.of(Order::getSalary));

        assertThat(op.operation()).isEqualTo(expected);
        assertThat(op.arguments()).containsExactly(right);
        assertThat(op.subject()).isNull();
        assertThat(read(op.refs())).isEqualTo(SALARY);
    }

    @Test
    @DisplayName("算术链：每一层的 subject 指向内层算术值，refs 始终是最底层的路径")
    void arithmeticChain() {
        NumberValue<Order, Double> root = Path.of(Order::getSalary).add(1000.0);
        NumberValue<Order, Double> add = root.add(2.0);
        NumberValue<Order, Double> sub = add.sub(10.0);
        NumberValue<Order, Double> mul = sub.mul(2.0);
        NumberValue<Order, Double> div = mul.div(4.0);
        NumberValue<Order, Double> mod = div.mod(3.0);

        assertThat(((Op) root).subject()).isNull();
        assertThat(((Op) root).operation()).isEqualTo(Operation.ADD);
        assertThat(((Op) root).arguments()).containsExactly(1000.0);

        // 算术值上的 add 与路径上的 add 是两个不同的组装入口，链的形状必须一样
        assertThat(((Op) add).operation()).isEqualTo(Operation.ADD);
        assertThat(((Op) add).arguments()).containsExactly(2.0);
        assertThat(((Op) add).subject()).isSameAs(root);

        assertThat(((Op) sub).operation()).isEqualTo(Operation.SUB);
        assertThat(((Op) sub).arguments()).containsExactly(10.0);
        assertThat(((Op) sub).subject()).isSameAs(add);

        assertThat(((Op) mul).operation()).isEqualTo(Operation.MUL);
        assertThat(((Op) mul).arguments()).containsExactly(2.0);
        assertThat(((Op) mul).subject()).isSameAs(sub);

        assertThat(((Op) div).operation()).isEqualTo(Operation.DIV);
        assertThat(((Op) div).arguments()).containsExactly(4.0);
        assertThat(((Op) div).subject()).isSameAs(mul);

        assertThat(((Op) mod).operation()).isEqualTo(Operation.MOD);
        assertThat(((Op) mod).arguments()).containsExactly(3.0);
        assertThat(((Op) mod).subject()).isSameAs(div);
        assertThat(read(((Op) mod).refs())).isEqualTo(SALARY);
    }

    private static Stream<Arguments> arithmeticRows() {
        return Stream.of(
                Arguments.of(
                        "add", Operation.ADD, 1000.0, (Function<NumberPath<Order, Double>, NumberValue<Order, Double>>)
                                salary -> salary.add(1000.0)),
                Arguments.of(
                        "sub", Operation.SUB, 10.0, (Function<NumberPath<Order, Double>, NumberValue<Order, Double>>)
                                salary -> salary.sub(10.0)),
                Arguments.of(
                        "mul", Operation.MUL, 12.0, (Function<NumberPath<Order, Double>, NumberValue<Order, Double>>)
                                salary -> salary.mul(12.0)),
                Arguments.of(
                        "div", Operation.DIV, 4.0, (Function<NumberPath<Order, Double>, NumberValue<Order, Double>>)
                                salary -> salary.div(4.0)),
                Arguments.of(
                        "mod", Operation.MOD, 3.0, (Function<NumberPath<Order, Double>, NumberValue<Order, Double>>)
                                salary -> salary.mod(3.0)));
    }
}
