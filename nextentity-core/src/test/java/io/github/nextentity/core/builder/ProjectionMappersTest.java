package io.github.nextentity.core.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.Path;
import io.github.nextentity.api.ProjectionMapper;
import io.github.nextentity.api.ProjectionMappers;
import io.github.nextentity.api.model.Tuple;
import io.github.nextentity.api.model.Tuple2;
import io.github.nextentity.api.model.Tuple3;
import io.github.nextentity.api.model.Tuple4;
import io.github.nextentity.api.model.Tuple5;
import io.github.nextentity.api.model.Tuple6;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.EntitySelection;
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

        // 消息点名「三列」与越界下标本身：只断类型时，把某个元组类的拒绝分支抄错成别的元数
        // （`两列元组没有下标` 一类 copy-paste）、或把下标信息丢掉，都照样通过。
        assertThatThrownBy(() -> three.get(3))
                .isInstanceOf(IndexOutOfBoundsException.class)
                .hasMessage("三列元组没有下标 3");
        assertThatThrownBy(() -> three.get(-1))
                .isInstanceOf(IndexOutOfBoundsException.class)
                .hasMessage("三列元组没有下标 -1");
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
    @DisplayName("ProjectionMappers：五列工厂的列序与末列对位（四列与六列之间不留缝）")
    void fiveColumnFactoryKeepsColumnOrder() {
        ProjectionMapper<Order, Tuple5<Long, String, Integer, Double, Long>> mapper = ProjectionMappers.select(
                Path.of(Order::getId),
                Path.of(Order::getStatus),
                Path.of(Order::getAge),
                Path.of(Order::getScore),
                Path.of(Order::getOrgId));

        Tuple5<Long, String, Integer, Double, Long> row =
                mapper.map(new TupleValue(new Object[] {1L, "PAID", 7, 9.5d, 2L}));

        assertThat(mapper.expressions()).hasSize(5);
        assertThat(row).isEqualTo(new Tuple5<>(1L, "PAID", 7, 9.5d, 2L));
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
    @DisplayName("内置映射器：单列给裸值，双/三列给具名元组，与 select(...) 的返回类型一致")
    void valueMappersProduceSelectorResultShapes() {
        Tuple single = new SingleTuple(1L);
        assertThat(ValueMappers.single().map(single)).isEqualTo(1L);

        Tuple pair = new Tuple2<>(1L, "PAID");
        assertThat(ValueMappers.pair().map(pair)).isEqualTo(new Tuple2<>(1L, "PAID"));

        Tuple triple = new TupleValue(new Object[] {1L, "PAID", 7});
        assertThat(ValueMappers.triple().map(triple)).isEqualTo(new Tuple3<>(1L, "PAID", 7));
    }

    @Test
    @DisplayName("实体查询：装配不复制行，直接把执行器的列表交出去")
    void entitySelectionPassesRowsThrough() {
        List<Object> rows = List.of((Object) new String[] {"a", "b"}, "bare");

        assertThat(RowAssembler.assemble(rows, new EntitySelection())).isSameAs(rows);
    }

    @Test
    @DisplayName("单列装配：长度 1 的 Object[] 与裸值都收成同一个列值，整行就是那一列")
    void singleColumnUnwrapsOnlyLengthOneArray() {
        ValueSelection oneColumn = new ValueSelection(
                List.of(new PathProjection(AttributePath.of(Order.class, "id"))), false, ValueMappers.single());

        assertThat(RowAssembler.assemble(List.of((Object) new Object[] {1L}), oneColumn))
                .containsExactly(1L);
        assertThat(RowAssembler.assemble(List.of((Object) 1L), oneColumn)).containsExactly(1L);
        // 整行一个值、恰好是两元素数组时，行的 cell 数与投影列数不等：当场拒绝，不按行长度猜形态
        assertThatThrownBy(() -> RowAssembler.assemble(List.of((Object) new Object[] {1L, 2L}), oneColumn))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("行 cell 数与投影列数不等")
                .hasMessageContaining("行 2 个 cell")
                .hasMessageContaining("投影 1 列");
    }

    @Test
    @DisplayName("单列映射器拿到的行已经是「1 个 cell」：取第 0 位即可，不在这里再判形态")
    void singleColumnMapperReceivesOneCellRow() {
        assertThat(ValueMappers.single().map(new SingleTuple(new Object[] {1L})))
                .isEqualTo(new Object[] {1L});
        assertThat(ValueMappers.single().map(new SingleTuple(1L))).isEqualTo(1L);
    }

    @Test
    @DisplayName("SingleTuple：长度恒为 1，0 以外的下标一律越界（不让单列元组伪装成多列）")
    void singleTupleRejectsAnyIndexBeyondZero() {
        SingleTuple tuple = new SingleTuple(null);

        assertThat(tuple.size()).isEqualTo(1);
        assertThat(tuple.get(0)).isNull();
        assertThatThrownBy(() -> tuple.get(1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> tuple.get(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    @DisplayName("TupleValue：长度随数组，越界由数组自身拒绝")
    void tupleValueReportsArrayLength() {
        Tuple tuple = new TupleValue(new Object[] {"a", "b"});

        assertThat(tuple.size()).isEqualTo(2);
        assertThat(tuple.get(1)).isEqualTo("b");
        assertThatThrownBy(() -> tuple.get(2)).isInstanceOf(ArrayIndexOutOfBoundsException.class);
    }

    @Test
    @DisplayName("两列装配：直接押进 Tuple2，不走 TupleValue 的数组包装")
    void twoColumnsAssembleStraightIntoNamedTuple() {
        ValueSelection twoColumns = new ValueSelection(
                List.of(
                        new PathProjection(AttributePath.of(Order.class, "id")),
                        new PathProjection(AttributePath.of(Order.class, "name"))),
                false,
                ValueMappers.pair());

        assertThat(RowAssembler.assemble(List.of((Object) new Object[] {1L, "a"}), twoColumns))
                .containsExactly(new Tuple2<>(1L, "a"));
    }

    @Test
    @DisplayName("多列装配：非数组行整行作为唯一 cell，cell 数与投影列数不等即拒绝")
    void multiColumnRejectsNonArrayRow() {
        ValueSelection twoColumns = new ValueSelection(
                List.of(
                        new PathProjection(AttributePath.of(Order.class, "id")),
                        new PathProjection(AttributePath.of(Order.class, "name"))),
                false,
                ValueMappers.pair());

        // 后端异常地给了裸值行：整行当唯一 cell（1 个），与 2 列投影不等，当场拒绝而不是等到取列
        assertThatThrownBy(() -> RowAssembler.assemble(List.of((Object) 1L), twoColumns))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("行 cell 数与投影列数不等")
                .hasMessageContaining("1")
                .hasMessageContaining("2");
    }

    /// 3 列投影遇到长度 2 的行：旧实现按投影列数造 `TupleValue`，`get(2)` 抛 `ArrayIndexOutOfBoundsException`
    /// ——后端错行当场暴露。改成「按行长度猜形态」后同一份数据会被静默装配成 `Tuple2`，`get(2)` 拿到第二列值。
    /// 这里把「行形态与列数不符必须拒绝、且拒绝点比取列更早」钉死。
    @Test
    @DisplayName("三列装配：长度 2 的行被拒绝，不静默装配成 Tuple2")
    void shortRowForThreeColumnsIsRejected() {
        ValueSelection threeColumns = new ValueSelection(
                List.of(
                        new PathProjection(AttributePath.of(Order.class, "id")),
                        new PathProjection(AttributePath.of(Order.class, "name")),
                        new PathProjection(AttributePath.of(Order.class, "status"))),
                false,
                ValueMappers.triple());

        assertThatThrownBy(() -> RowAssembler.assemble(List.of((Object) new Object[] {1L, "a"}), threeColumns))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("行 cell 数与投影列数不等")
                .hasMessageContaining("2")
                .hasMessageContaining("3");
    }

    @Test
    @DisplayName("投影宽度：四列投影经映射器逐列装配（新增形态 = 加一个映射器，不改装配分支）")
    void fourColumnsAssembleWithoutColumnLimit() {
        ValueSelection fourColumns = new ValueSelection(
                List.of(
                        new PathProjection(AttributePath.of(Order.class, "id")),
                        new PathProjection(AttributePath.of(Order.class, "name")),
                        new PathProjection(AttributePath.of(Order.class, "status")),
                        new PathProjection(AttributePath.of(Order.class, "age"))),
                false,
                ProjectionMappers.select(
                        Path.of(Order::getId),
                        Path.of(Order::getName),
                        Path.of(Order::getStatus),
                        Path.of(Order::getAge))::map);

        assertThat(RowAssembler.assemble(List.of((Object) new Object[] {1L, "a", "b", 2}), fourColumns))
                .containsExactly(new Tuple4<>(1L, "a", "b", 2));
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
