package io.github.nextentity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.WireFormatFixture.Order;
import io.github.nextentity.api.model.Tuple;
import io.github.nextentity.api.model.Tuple2;
import io.github.nextentity.api.model.Tuple4;
import io.github.nextentity.api.model.Tuple5;
import io.github.nextentity.api.model.Tuple6;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 投影映射器的线格式：`expressions()` 的列序就是 `map(Tuple)` 的取值下标，`andThen` 只换结果类型不换列集。
///
/// 本套件钉的是 **`api` 自己的线格式契约**（列集与列序）。「这些表达式如何被渲染成 SQL 的 SELECT 列序」
/// 的证据在 `jpa` 的渲染用例，两层不互相代替；这里只证明 `api` 交给后端的列集与它自己取值时的列序
/// 是同一份，不会错配。
@DisplayName("ProjectionMappers / ProjectionMapper：列集与列序")
class ProjectionMapperWireFormatTest {

    @Test
    @DisplayName("四列：expressions 就是入参顺序，map 按下标装配 Tuple4")
    void fourColumns() {
        Path<Order, Integer> age = Path.of(Order::getAge);
        Path<Order, Integer> score = Path.of(Order::getScore);
        Path<Order, Integer> level = Path.of(Order::getLevel);
        Path<Order, String> status = Path.of(Order::getStatus);

        ProjectionMapper<Order, Tuple4<Integer, Integer, Integer, String>> mapper =
                ProjectionMappers.select(age, score, level, status);

        assertThat(mapper.expressions()).containsExactly(age, score, level, status);
        assertThat(mapper.map(new Tuple4<>(11, 22, 33, "S"))).isEqualTo(new Tuple4<>(11, 22, 33, "S"));
    }

    @Test
    @DisplayName("五列：expressions 就是入参顺序，map 按下标装配 Tuple5")
    void fiveColumns() {
        Path<Order, Integer> age = Path.of(Order::getAge);
        Path<Order, Integer> score = Path.of(Order::getScore);
        Path<Order, Integer> level = Path.of(Order::getLevel);
        Path<Order, String> status = Path.of(Order::getStatus);
        Path<Order, Double> salary = Path.of(Order::getSalary);

        ProjectionMapper<Order, Tuple5<Integer, Integer, Integer, String, Double>> mapper =
                ProjectionMappers.select(age, score, level, status, salary);

        assertThat(mapper.expressions()).containsExactly(age, score, level, status, salary);
        assertThat(mapper.map(new Tuple5<>(11, 22, 33, "S", 44.5))).isEqualTo(new Tuple5<>(11, 22, 33, "S", 44.5));
    }

    @Test
    @DisplayName("六列：expressions 就是入参顺序，map 按下标装配 Tuple6")
    void sixColumns() {
        Path<Order, Integer> age = Path.of(Order::getAge);
        Path<Order, Integer> score = Path.of(Order::getScore);
        Path<Order, Integer> level = Path.of(Order::getLevel);
        Path<Order, String> status = Path.of(Order::getStatus);
        Path<Order, Double> salary = Path.of(Order::getSalary);
        Path<Order, Double> bonus = Path.of(Order::getBonus);

        ProjectionMapper<Order, Tuple6<Integer, Integer, Integer, String, Double, Double>> mapper =
                ProjectionMappers.select(age, score, level, status, salary, bonus);

        assertThat(mapper.expressions()).containsExactly(age, score, level, status, salary, bonus);
        assertThat(mapper.map(new Tuple6<>(11, 22, 33, "S", 44.5, 55.5)))
                .isEqualTo(new Tuple6<>(11, 22, 33, "S", 44.5, 55.5));
    }

    @Test
    @DisplayName("andThen：列集原样委托，map 先取原结果再套一层函数")
    void andThenDerivesMapper() {
        Path<Order, Integer> age = Path.of(Order::getAge);
        ProjectionMapper<Order, Object> first = new ProjectionMapper<>() {
            @Override
            public List<Expression<Order, ?>> expressions() {
                return List.<Expression<Order, ?>>of(age);
            }

            @Override
            public Object map(Tuple row) {
                return row.get(0);
            }
        };

        ProjectionMapper<Order, String> decorated = first.andThen(value -> "v=" + value);

        assertThat(decorated.expressions()).containsExactly(age);
        assertThat(decorated.map(new Tuple2<>(7, "x"))).isEqualTo("v=7");
    }

    @Test
    @DisplayName("andThen(null)：入参校验消息点名 after")
    void andThenRejectsNullFunction() {
        ProjectionMapper<Order, Object> mapper = new ProjectionMapper<>() {
            @Override
            public List<Expression<Order, ?>> expressions() {
                return List.of();
            }

            @Override
            public Object map(Tuple row) {
                return row.get(0);
            }
        };

        assertThatThrownBy(() -> mapper.andThen(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("after");
    }
}
