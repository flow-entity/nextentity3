package io.github.nextentity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.model.Tuple;
import io.github.nextentity.api.model.Tuple2;
import io.github.nextentity.api.model.Tuple3;
import io.github.nextentity.api.model.Tuple4;
import io.github.nextentity.api.model.Tuple5;
import io.github.nextentity.api.model.Tuple6;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/// `Tuple2`~`Tuple6` 的线格式：`size()`、按位置的 `get(int)`、具名访问器三者给出同一组列值。
///
/// 本套件钉的是 **`api` 自己的线格式契约**（列序 ↔ 取值下标）。投影映射器的列序断言在
/// `ProjectionMapperWireFormatTest`，「投影表达式 → SQL 列序」的证据在 `core`/`jpa` 的渲染用例，
/// 三层不互相代替。
///
/// 三条入口都要钉：只断言 `get(int)` 证明不了具名访问器（`get0()` 等）与位置一致，反过来也一样；
/// 数值下标越界必须报出中文列数与**具体下标**，否则调用方拿不到「有几列」这一关键信息。
@DisplayName("Tuple2~Tuple6：列数与按位置取值")
class TuplePositionalAccessTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("tuples")
    @DisplayName("size / get(int) / 具名访问器给出同一组列值")
    void positionalAccess(String name, Tuple tuple, List<Object> columns, String overflowPrefix) {
        assertThat(tuple.size()).isEqualTo(columns.size());
        for (int index = 0; index < columns.size(); index++) {
            assertThat(tuple.get(index)).isEqualTo(columns.get(index));
        }
        assertThat(namedColumns(tuple)).isEqualTo(columns);

        assertThatThrownBy(() -> tuple.get(columns.size()))
                .isInstanceOf(IndexOutOfBoundsException.class)
                .hasMessage(overflowPrefix + columns.size());
        assertThatThrownBy(() -> tuple.get(-1))
                .isInstanceOf(IndexOutOfBoundsException.class)
                .hasMessage(overflowPrefix + "-1");
    }

    @Test
    @DisplayName("元组是 record：列值相等即相等，且哈希一致")
    void valueSemantics() {
        assertThat(new Tuple2<>("a", 1)).isEqualTo(new Tuple2<>("a", 1));
        assertThat(new Tuple3<>("a", 1, 2L)).hasSameHashCodeAs(new Tuple3<>("a", 1, 2L));
        assertThat(new Tuple4<>("a", 1, 2L, 3.0)).isEqualTo(new Tuple4<>("a", 1, 2L, 3.0));
        assertThat(new Tuple5<>("a", 1, 2L, 3.0, true)).hasSameHashCodeAs(new Tuple5<>("a", 1, 2L, 3.0, true));
        assertThat(new Tuple6<>("a", 1, 2L, 3.0, true, 'x')).isEqualTo(new Tuple6<>("a", 1, 2L, 3.0, true, 'x'));
    }

    /// 具名访问器的取值，按元组宽度分派：这条通路与 `get(int)` 完全独立，必须各钉一次。
    private static List<Object> namedColumns(Tuple tuple) {
        return switch (tuple) {
            case Tuple2<?, ?> two -> Arrays.asList(two.get0(), two.get1());
            case Tuple3<?, ?, ?> three -> Arrays.asList(three.get0(), three.get1(), three.get2());
            case Tuple4<?, ?, ?, ?> four -> Arrays.asList(four.get0(), four.get1(), four.get2(), four.get3());
            case Tuple5<?, ?, ?, ?, ?> five ->
                Arrays.asList(five.get0(), five.get1(), five.get2(), five.get3(), five.get4());
            case Tuple6<?, ?, ?, ?, ?, ?> six ->
                Arrays.asList(six.get0(), six.get1(), six.get2(), six.get3(), six.get4(), six.get5());
            default -> throw new IllegalArgumentException("未收录的元组形态：" + tuple);
        };
    }

    private static Stream<Arguments> tuples() {
        return Stream.of(
                Arguments.of("Tuple2", new Tuple2<>("a", 1), List.of("a", 1), "两列元组没有下标 "),
                Arguments.of("Tuple3", new Tuple3<>("a", 1, 2L), List.of("a", 1, 2L), "三列元组没有下标 "),
                Arguments.of("Tuple4", new Tuple4<>("a", 1, 2L, 3.0), List.of("a", 1, 2L, 3.0), "四列元组没有下标 "),
                Arguments.of(
                        "Tuple5", new Tuple5<>("a", 1, 2L, 3.0, true), List.of("a", 1, 2L, 3.0, true), "五列元组没有下标 "),
                Arguments.of(
                        "Tuple6",
                        new Tuple6<>("a", 1, 2L, 3.0, true, 'x'),
                        List.of("a", 1, 2L, 3.0, true, 'x'),
                        "六列元组没有下标 "));
    }
}
