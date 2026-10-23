package io.github.nextentity.core.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.Expression;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.api.ProjectionMapper;
import io.github.nextentity.api.model.Tuple;
import io.github.nextentity.api.model.Tuple2;
import io.github.nextentity.core.compile.Compiler;
import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.core.meta.EntityAttribute;
import io.github.nextentity.core.meta.EntityType;
import io.github.nextentity.core.spec.AggregateFunction;
import io.github.nextentity.core.spec.AggregateProjection;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.PathProjection;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.ValueSelection;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `SelectStep#project(ProjectionMapper)` 的 core 侧断言：不连接数据库，用"编译器即恒等 + 执行器记账"的假后端。
///
/// 这里只钉规格形状与装配约定。真实 SQL 上的语义由一致性用例钉住
/// （`nextentity-testing/src/main/java/io/github/nextentity/testing/ProjectionMapperConsistencyTest.java`）：
/// `aggregateWithoutGroupByReturnsSingleRow`、`aggregateOverEmptyTableStillReturnsOneRow`、
/// `countRejectsAggregateMapperQuery` / `sliceRejectsAggregateMapperQuery`。
@DisplayName("ProjectionMapper → Spec 与装配")
class ProjectionMapperTest {

    private final Recorder backend = new Recorder();

    private QueryBuilder<Order, QuerySpec> query() {
        return new QueryBuilder<>(meta(), backend, backend);
    }

    @Test
    @DisplayName("单列 mapper：selection 只有一项，单列标量行装配成长度为 1 的 Tuple")
    void singleColumnMapper() {
        backend.rows = List.of((Object) BigDecimal.TEN);

        List<AmountTotal> totals = query().project(new AmountTotalMapper()).list();

        assertThat(((ValueSelection) backend.last().selection()).items())
                .containsExactly(new AggregateProjection(AggregateFunction.SUM, path("amount")));
        assertThat(totals).containsExactly(new AmountTotal(BigDecimal.TEN, 1));
    }

    @Test
    @DisplayName("单列 + 列值本身是两元素数组：仍按 1 列装配，get(0) 就是那个数组")
    void singleColumnWithArrayValue() {
        Object value = new Object[] {1L, 2L};
        backend.rows = List.of(value);

        List<ArrayColumn> rows = query().project(new ArrayColumnMapper()).list();

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().columns()).isEqualTo(1);
        assertThat(rows.getFirst().value()).isSameAs(value);
    }

    @Test
    @DisplayName("expressions() 用裸方法引用（PathRef）：与 Path.of(...) 一样落 PathProjection")
    void bareMethodReferenceExpression() {
        backend.rows = List.of((Object) 42L);

        List<Long> ids = query().project(new BareReferenceMapper()).list();

        assertThat(((ValueSelection) backend.last().selection()).items())
                .containsExactly(new PathProjection(path("id")));
        assertThat(ids).containsExactly(42L);
    }

    @Test
    @DisplayName("聚合投影 + 无 groupBy：规格带聚合标志、groupBy 为空（SQL 退化成整表一个分组）")
    void aggregateWithoutGroupBy() {
        backend.rows = List.of((Object) BigDecimal.TEN);

        query().project(new AmountTotalMapper()).list();

        QuerySpec spec = backend.last();
        assertThat(spec.aggregate()).isTrue();
        assertThat(spec.groupBy()).isEmpty();
    }

    @Test
    @DisplayName("expressions() 为空：project() 阶段抛 IllegalArgumentException，且不会向执行器发出查询")
    void emptyExpressionsRejected() {
        assertThatThrownBy(() -> query().project(new EmptyMapper()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("投影映射器的 expressions() 不能为空");
        assertThat(backend.specs).isEmpty();
    }

    @Test
    @DisplayName("expressions() 含 null：抛 IllegalArgumentException，消息带该项下标")
    void nullExpressionRejectedWithIndex() {
        assertThatThrownBy(() -> query().project(new NullExpressionMapper()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("投影映射器的 expressions() 第 1 项为 null");
    }

    @Test
    @DisplayName("expressions() 含库无法解释的表达式：抛 IllegalArgumentException 带下标，不是 NPE/ClassCastException")
    void foreignExpressionRejectedWithIndex() {
        assertThatThrownBy(() -> query().project(new ForeignExpressionMapper()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("投影映射器的 expressions() 第 0 项不是库可解释的表达式: " + ForeignExpression.class.getName());
    }

    @Test
    @DisplayName("project(null)：抛 NullPointerException（调用方传错参）")
    void nullMapperRejected() {
        assertThatThrownBy(() -> query().project(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("mapper");
    }

    @Test
    @DisplayName("expressions() 返回 null：抛 NullPointerException 且消息带成员名（实现方违约）")
    void nullExpressionsRejected() {
        assertThatThrownBy(() -> query().project(new NullListMapper()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("expressions()");
    }

    @Test
    @DisplayName("同一 mapper 实例连跑两次查询：结果一致（映射器与框架都不留状态）")
    void sameMapperInstanceReused() {
        ReportMapper mapper = new ReportMapper();
        backend.rows = List.of((Object) new Object[] {7L, BigDecimal.ONE});

        List<ReportMapper.Row> first = query().project(mapper).list();
        List<ReportMapper.Row> second = query().project(mapper).list();

        assertThat(first).isEqualTo(second).containsExactly(new ReportMapper.Row(7L, BigDecimal.ONE));
    }

    @Test
    @DisplayName("expressions() 每次 project() 只求值一次（框架不跨查询缓存）")
    void expressionsEvaluatedOnce() {
        CountingMapper mapper = new CountingMapper("A");
        backend.rows = List.of((Object) new Object[] {1L, "PAID"});

        query().project(mapper).list();

        assertThat(mapper.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("同一 builder 上 project(m1) 再 project(m2)：后者生效，两者 expressions() 各求值一次")
    void laterProjectReplacesEarlier() {
        CountingMapper first = new CountingMapper("A");
        CountingMapper second = new CountingMapper("B");
        backend.rows = List.of((Object) new Object[] {1L, "PAID"});
        QueryBuilder<Order, QuerySpec> builder = query();

        builder.project(first);

        assertThat(builder.project(second).list()).containsExactly("B1");
        assertThat(first.calls).isEqualTo(1);
        assertThat(second.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("聚合 mapper 的 count()：交给后端的规格带聚合标志（后端据此拒绝计数）")
    void aggregateMapperCountCarriesAggregateFlag() {
        backend.total = 1L;

        query().project(new AmountTotalMapper()).count();

        // 拒绝计数的是 jpa 的执行器（JpaExecutor#count 见 aggregate() 即抛 UnsupportedOperationException），
        // core 只负责让 mapper 产出的规格带上这个标志，否则那道护栏对它就是空转
        assertThat(backend.specs).hasSize(1);
        assertThat(backend.last().aggregate()).isTrue();
    }

    @Test
    @DisplayName("聚合 mapper 的 slice()：计数与取行两条规格都带聚合标志")
    void aggregateMapperSliceCarriesAggregateFlag() {
        backend.total = 1L;
        backend.rows = List.of((Object) BigDecimal.TEN);

        query().project(new AmountTotalMapper()).slice(0, 10);

        assertThat(backend.specs).hasSize(2);
        assertThat(backend.specs)
                .allSatisfy(spec -> assertThat(spec.aggregate()).isTrue());
    }

    @Test
    @DisplayName("project 之后再 select：映射器失效，取行不再经过 map（后者覆盖前者）")
    void selectAfterProjectDropsMapper() {
        backend.rows = List.of((Object) new Object[] {1L, "PAID"});
        QueryBuilder<Order, QuerySpec> builder = query();
        builder.project(new ReportMapper());

        List<Tuple2<Long, String>> rows =
                builder.select(Path.of(Order::getId), Path.of(Order::getStatus)).list();

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).isInstanceOf(Tuple2.class);
        assertThat(rows.getFirst().get0()).isEqualTo(1L);
        assertThat(rows.getFirst().get1()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("project 之后再 distinct().select：映射器同样失效，取行是裸值列表且规格带 distinct")
    void distinctSelectAfterProjectDropsMapper() {
        backend.rows = List.of((Object) "PAID");
        QueryBuilder<Order, QuerySpec> builder = query();
        builder.project(new ReportMapper());

        List<String> statuses = builder.distinct().select(Order::getStatus).list();

        ValueSelection selection = (ValueSelection) backend.last().selection();
        assertThat(selection.distinct()).isTrue();
        assertThat(selection.items()).containsExactly(new PathProjection(path("status")));
        assertThat(statuses).containsExactly("PAID");
    }

    // ---------------------------------------------------------------- fixture

    private static AttributePath path(String name) {
        return AttributePath.of(Order.class, name);
    }

    private static EntityType<Order> meta() {
        return new EntityType<>() {
            private final EntityAttribute id = attribute("id", Long.class);

            @Override
            public Class<Order> type() {
                return Order.class;
            }

            @Override
            public EntityAttribute id() {
                return id;
            }

            @Override
            public EntityAttribute version() {
                return null;
            }
        };
    }

    private static EntityAttribute attribute(String name, Class<?> type) {
        return new EntityAttribute() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Class<?> type() {
                return type;
            }

            @Override
            public Object get(Object entity) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void set(Object entity, Object value) {
                throw new UnsupportedOperationException();
            }
        };
    }

    /// 映射结果带上列数，让"单列也装配成 Tuple"这件事在断言里可见。
    private record AmountTotal(BigDecimal amount, int columns) {}

    /// 单列（`sum(amount)`）：列数下限是 1，不是 2。
    private static final class AmountTotalMapper implements ProjectionMapper<Order, AmountTotal> {

        @Override
        public List<Expression<Order, ?>> expressions() {
            return List.of(Path.of(Order::getAmount).sum());
        }

        @Override
        public AmountTotal map(Tuple row) {
            return new AmountTotal((BigDecimal) row.get(0), row.size());
        }
    }

    /// 单列，但列值本身是 `Object[]`：后端只对长度 1 的数组做单列拆包，这种行必须仍算 1 列。
    private static final class ArrayColumnMapper implements ProjectionMapper<Order, ArrayColumn> {

        @Override
        public List<Expression<Order, ?>> expressions() {
            return List.of(Path.of(Order::getId));
        }

        @Override
        public ArrayColumn map(Tuple row) {
            return new ArrayColumn(row.size(), row.get(0));
        }
    }

    private record ArrayColumn(int columns, Object value) {}

    /// `expressions()` 用裸方法引用：`PathRef` 也是 `Expression`，是最自然的写法。
    private static final class BareReferenceMapper implements ProjectionMapper<Order, Long> {

        @Override
        public List<Expression<Order, ?>> expressions() {
            PathRef<Order, Long> id = Order::getId;
            return List.of(id);
        }

        @Override
        public Long map(Tuple row) {
            return (Long) row.get(0);
        }
    }

    /// 两列（属性 + 聚合），下标常量与 `expressions()` 相邻，正是契约建议的写法。
    private static final class ReportMapper implements ProjectionMapper<Order, ReportMapper.Row> {

        private static final int IDX_ID = 0;
        private static final int IDX_AMOUNT = 1;

        @Override
        public List<Expression<Order, ?>> expressions() {
            return List.of(Path.of(Order::getId), Path.of(Order::getAmount).sum());
        }

        @Override
        public Row map(Tuple row) {
            return new Row((Long) row.get(IDX_ID), (BigDecimal) row.get(IDX_AMOUNT));
        }

        record Row(Long id, BigDecimal amount) {}
    }

    private static final class EmptyMapper implements ProjectionMapper<Order, Object> {

        @Override
        public List<Expression<Order, ?>> expressions() {
            return List.of();
        }

        @Override
        public Object map(Tuple row) {
            return row;
        }
    }

    private static final class NullExpressionMapper implements ProjectionMapper<Order, Object> {

        @Override
        public List<Expression<Order, ?>> expressions() {
            return Arrays.<Expression<Order, ?>>asList(Path.of(Order::getId), null);
        }

        @Override
        public Object map(Tuple row) {
            return row;
        }
    }

    /// 违约实现：`expressions()` 返回 `null`（契约要求非空列表）。
    private static final class NullListMapper implements ProjectionMapper<Order, Object> {

        @Override
        public List<Expression<Order, ?>> expressions() {
            return null;
        }

        @Override
        public Object map(Tuple row) {
            return row;
        }
    }

    /// 非库产出的表达式：既不是 `Path` 也不是 `Op`，后端无法解释。
    private record ForeignExpression() implements Expression<Order, String> {}

    private static final class ForeignExpressionMapper implements ProjectionMapper<Order, Object> {

        @Override
        public List<Expression<Order, ?>> expressions() {
            return List.of(new ForeignExpression());
        }

        @Override
        public Object map(Tuple row) {
            return row;
        }
    }

    /// 只为了数 `expressions()` 的调用次数才带状态，映射逻辑本身无状态。
    ///
    /// `label` 让「哪个 mapper 生效」在结果值上可辨，两个实例的表达式完全相同。
    private static final class CountingMapper implements ProjectionMapper<Order, Object> {

        private final String label;
        private int calls;

        CountingMapper(String label) {
            this.label = label;
        }

        @Override
        public List<Expression<Order, ?>> expressions() {
            calls++;
            return List.of(Path.of(Order::getId), Path.of(Order::getStatus));
        }

        @Override
        public Object map(Tuple row) {
            return label + row.get(0);
        }
    }

    /// 假后端：编译恒等（`S = QuerySpec`），执行只记账。
    private static final class Recorder implements Compiler<QuerySpec>, Executor<QuerySpec> {

        private final List<QuerySpec> specs = new ArrayList<>();
        private List<Object> rows = List.of();
        private long total;

        @Override
        public QuerySpec compile(QuerySpec spec) {
            return spec;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> List<R> list(QuerySpec query) {
            specs.add(query);
            return (List<R>) rows;
        }

        @Override
        public long count(QuerySpec query) {
            specs.add(query);
            return total;
        }

        @Override
        public boolean exists(QuerySpec query) {
            specs.add(query);
            return false;
        }

        QuerySpec last() {
            return specs.getLast();
        }
    }

    /// 测试实体：只需要 getter 名，不需要任何 JPA 注解。
    @SuppressWarnings("unused")
    private static final class Order {

        private Long id;
        private String status;
        private BigDecimal amount;

        public Long getId() {
            return id;
        }

        public String getStatus() {
            return status;
        }

        public BigDecimal getAmount() {
            return amount;
        }
    }
}
