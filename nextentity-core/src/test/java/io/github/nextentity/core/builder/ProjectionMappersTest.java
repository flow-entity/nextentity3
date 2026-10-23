package io.github.nextentity.core.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.Path;
import io.github.nextentity.api.ProjectionMapper;
import io.github.nextentity.api.ProjectionMappers;
import io.github.nextentity.api.model.Tuple2;
import io.github.nextentity.api.model.Tuple3;
import io.github.nextentity.api.model.Tuple4;
import io.github.nextentity.api.model.Tuple5;
import io.github.nextentity.api.model.Tuple6;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.PathProjection;
import io.github.nextentity.core.spec.ValueSelection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 具名元组族、`ProjectionMappers` 与 `andThen` 的结构断言：不连接数据库。
///
/// 三类证据分开：
/// - `TupleN` 是 record，`getN()` 与 `get(int)` 必须给出同一列、越界必须拒绝、按值相等；
/// - `ProjectionMappers.select(...)` 必须把列序原样交给 `expressions()`，并把同一列序装配回 `TupleN`；
/// - `andThen` 只换结果类型、不改列集，且派生映射器仍可复用。
///
/// 列序与类型的对应关系由 `ProjectionMappers` 里唯一那处转型保证，本类只钉住「同一列序」这条不变式。
@DisplayName("具名元组、ProjectionMappers 与 andThen")
class ProjectionMappersTest {

    @Test
    @DisplayName("Tuple2 与 Tuple3：size 与具名/按位置取值一致")
    void narrowTupleAccessors() {
        Tuple2<Long, String> pair = new Tuple2<>(1L, "PAID");
        Tuple3<Long, String, Integer> triple = new Tuple3<>(1L, "PAID", 7);

        assertThat(pair.size()).isEqualTo(2);
        assertThat(pair.get0()).isEqualTo(1L);
        assertThat(pair.get(1)).isEqualTo(pair.get1());
        assertThat(triple.size()).isEqualTo(3);
        assertThat(triple.get2()).isEqualTo(7);
        assertThat(triple.get(0)).isEqualTo(triple.get0());
    }

    @Test
    @DisplayName("Tuple5 与 Tuple6：列数加宽后 size 与末列仍对位")
    void wideTuplesKeepLastColumnAligned() {
        Tuple5<Long, String, Integer, Double, Long> five = new Tuple5<>(1L, "a", 2, 3.5d, 5L);
        Tuple6<Long, String, Integer, Double, Long, String> six = new Tuple6<>(1L, "a", 2, 3.5d, 5L, "b");

        assertThat(five.size()).isEqualTo(5);
        assertThat(five.get4()).isEqualTo(5L);
        assertThat(six.size()).isEqualTo(6);
        assertThat(six.get5()).isEqualTo("b");
        assertThat(six.get(5)).isEqualTo(six.get5());
    }

    @Test
    @DisplayName("越界下标一律拒绝，而不是返回 null 或抛裸数组异常")
    void outOfRangeIndexRejected() {
        Tuple3<Long, String, Integer> three = new Tuple3<>(1L, "PAID", 7);

        assertThatThrownBy(() -> three.get(3)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> three.get(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    @DisplayName("record 按值相等：同样的列值即相等的元组")
    void tuplesCompareByValue() {
        assertThat(new Tuple3<>(1L, "a", 2)).isEqualTo(new Tuple3<>(1L, "a", 2)).isNotEqualTo(new Tuple3<>(1L, "a", 3));
    }

    @Test
    @DisplayName("ProjectionMappers：expressions() 原样保留列序与列数")
    void expressionsKeepColumnOrder() {
        ProjectionMapper<Order, Tuple4<Long, String, Integer, Double>> mapper = ProjectionMappers.select(
                Path.of(Order::getId), Path.of(Order::getStatus), Path.of(Order::getAge), Path.of(Order::getScore));

        // Path 是值对象但没有 equals，只能按解析出的属性名比较列序
        assertThat(mapper.expressions()).hasSize(4);
        assertThat(mapper.expressions().stream()
                        .map(expression -> PathRefs.resolve(((Path<?, ?>) expression).refs())
                                .names()
                                .getFirst())
                        .toList())
                .containsExactly("id", "status", "age", "score");
    }

    @Test
    @DisplayName("ProjectionMappers：map 把同一列序装配成带类型的 TupleN")
    void mapsRowToTypedTuple() {
        ProjectionMapper<Order, Tuple4<Long, String, Integer, Double>> mapper = ProjectionMappers.select(
                Path.of(Order::getId), Path.of(Order::getStatus), Path.of(Order::getAge), Path.of(Order::getScore));

        Tuple4<Long, String, Integer, Double> row = mapper.map(new TupleValue(new Object[] {1L, "PAID", 7, 9.5d}));

        assertThat(row.size()).isEqualTo(4);
        assertThat(row.get0()).isEqualTo(1L);
        assertThat(row.get1()).isEqualTo("PAID");
        assertThat(row.get2()).isEqualTo(7);
        assertThat(row.get3()).isEqualTo(9.5d);
    }

    @Test
    @DisplayName("ProjectionMappers：六列是封顶宽度（参数上限 6），列序与末列仍对位")
    void widestFactoryKeepsColumnOrder() {
        ProjectionMapper<Order, Tuple6<Long, String, Integer, Double, Long, String>> mapper = ProjectionMappers.select(
                Path.of(Order::getId),
                Path.of(Order::getStatus),
                Path.of(Order::getAge),
                Path.of(Order::getScore),
                Path.of(Order::getOrgId),
                Path.of(Order::getName));

        Tuple6<Long, String, Integer, Double, Long, String> row =
                mapper.map(new TupleValue(new Object[] {1L, "PAID", 7, 9.5d, 2L, "DONE"}));

        assertThat(row.size()).isEqualTo(6);
        assertThat(row.get5()).isEqualTo("DONE");
    }

    @Test
    @DisplayName("andThen：只换结果类型，列集原样保留（仍 4 列）")
    void andThenKeepsExpressionsAndTransformsResult() {
        ProjectionMapper<Order, Tuple3<Long, String, Integer>> narrowed = ProjectionMappers.select(
                        Path.of(Order::getId),
                        Path.of(Order::getName),
                        Path.of(Order::getAge),
                        Path.of(Order::getScore))
                .andThen(tuple -> new Tuple3<>(tuple.get0(), tuple.get1(), tuple.get2()));

        assertThat(narrowed.expressions()).hasSize(4);
        assertThat(narrowed.map(new TupleValue(new Object[] {1L, "n", 7, 9.5d})))
                .isEqualTo(new Tuple3<>(1L, "n", 7));
    }

    @Test
    @DisplayName("非映射器路径超过三列：装配器拒绝，而不是静默截断")
    void rejectsMoreThanThreeColumnsWithoutMapper() {
        ValueSelection fourColumns = new ValueSelection(
                List.of(
                        new PathProjection(AttributePath.of(Order.class, "id")),
                        new PathProjection(AttributePath.of(Order.class, "name")),
                        new PathProjection(AttributePath.of(Order.class, "status")),
                        new PathProjection(AttributePath.of(Order.class, "age"))),
                false);

        assertThatThrownBy(() ->
                        RowAssembler.assemble(List.of((Object) new Object[] {1L, "a", "b", 2}), fourColumns, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1~3");
    }

    @SuppressWarnings("unused")
    private static final class Order {

        public Long getId() {
            return null;
        }

        public Long getOrgId() {
            return null;
        }

        public String getName() {
            return null;
        }

        public String getStatus() {
            return null;
        }

        public Integer getAge() {
            return null;
        }

        public Double getScore() {
            return null;
        }
    }
}
