package io.github.nextentity.core.builder;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.NumberPath;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.Predicate;
import io.github.nextentity.api.ProjectionMappers;
import io.github.nextentity.api.model.Tuple2;
import io.github.nextentity.api.model.Tuple3;
import io.github.nextentity.api.model.Tuple4;
import io.github.nextentity.core.compile.Compiler;
import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.core.meta.EntityAttribute;
import io.github.nextentity.core.meta.EntityType;
import io.github.nextentity.core.spec.AggregateFunction;
import io.github.nextentity.core.spec.AggregateOperand;
import io.github.nextentity.core.spec.AggregateProjection;
import io.github.nextentity.core.spec.ArithmeticOperand;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Between;
import io.github.nextentity.core.spec.BinaryOperator;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.ComputedProjection;
import io.github.nextentity.core.spec.Constant;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.Logical;
import io.github.nextentity.core.spec.LogicalOperator;
import io.github.nextentity.core.spec.Operand;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.Spec;
import io.github.nextentity.core.spec.ValueSelection;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 第一梯队能力的 AST 断言：列对列比较、算术、`not`、n 元 and/or、`notBetween`、
/// `having`、多列 `groupBy`、多列投影。
///
/// 全部走公开 DSL（`api` 的步骤接口 + `Path.of(...)`），断言落在 `core.spec` 节点上——
/// 不依赖数据库，也不依赖任何后端。
@DisplayName("第一梯队能力 → Spec")
class Tier1CapabilitiesTest {

    private final Recorder backend = new Recorder();

    private EntityQuery<Order> query() {
        return new QueryBuilder<>(meta(), backend, backend);
    }

    private Spec where() {
        return backend.last().where();
    }

    // ------------------------------------------------- 列对列比较（能力 1）

    @Test
    @DisplayName("where(列).eq(列)：两侧都是属性路径")
    void columnComparisonFromStepOperator() {
        query().where(Order::getOrgId).eq(Path.of(Order::getId)).list();

        assertThat(where()).isEqualTo(new Comparison(path("orgId"), ComparisonOperator.EQ, path("id")));
    }

    @Test
    @DisplayName("Path.of(列).gt(列)：表达式形态的列对列比较")
    void columnComparisonFromPath() {
        Predicate<Order> predicate = Path.of(Order::getSalary).gt(Path.of(Order::getBaseSalary));
        query().where(predicate).list();

        assertThat(where()).isEqualTo(new Comparison(path("salary"), ComparisonOperator.GT, path("baseSalary")));
    }

    // --------------------------------------------------- 算术（能力 2）

    @Test
    @DisplayName("列 + 值：落 ArithmeticOperand")
    void arithmeticAdd() {
        query().where(Path.of(Order::getSalary).add(1000.0).gt(50000.0)).list();

        Operand left = new ArithmeticOperand(BinaryOperator.ADD, path("salary"), Literal.of(1000.0));
        assertThat(where()).isEqualTo(new Comparison(left, ComparisonOperator.GT, Literal.of(50000.0)));
    }

    @Test
    @DisplayName("算术链 列 * 12 + 1000：嵌套 ArithmeticOperand，左结合")
    void arithmeticChain() {
        query().where(Path.of(Order::getSalary).mul(12.0).add(1000.0).gt(50000.0))
                .list();

        Operand mul = new ArithmeticOperand(BinaryOperator.MUL, path("salary"), Literal.of(12.0));
        Operand add = new ArithmeticOperand(BinaryOperator.ADD, mul, Literal.of(1000.0));
        assertThat(where()).isEqualTo(new Comparison(add, ComparisonOperator.GT, Literal.of(50000.0)));
    }

    @Test
    @DisplayName("算术投影：select(列 * 值) 落 ComputedProjection")
    void arithmeticProjection() {
        query().select(Path.of(Order::getSalary).mul(12.0)).list();

        ValueSelection selection = (ValueSelection) backend.last().selection();
        Operand expected = new ArithmeticOperand(BinaryOperator.MUL, path("salary"), Literal.of(12.0));
        assertThat(selection.items()).containsExactly(new ComputedProjection(expected));
    }

    // ------------------------------------------------- 聚合比较（能力 8）

    @Test
    @DisplayName("聚合 > 值：左侧落 AggregateOperand")
    void aggregateComparison() {
        query().where(Path.of(Order::getSalary).max().gt(50000.0)).list();

        Operand left = new AggregateOperand(AggregateFunction.MAX, path("salary"));
        assertThat(where()).isEqualTo(new Comparison(left, ComparisonOperator.GT, Literal.of(50000.0)));
    }

    @Test
    @DisplayName("min 挂在通用 Path 上，avg 只挂在 NumberPath 上")
    void minAndAvgProjection() {
        query().select(Path.of(Order::getStatus).min()).list();
        assertThat(((ValueSelection) backend.last().selection()).items())
                .containsExactly(new AggregateProjection(AggregateFunction.MIN, path("status")));

        NumberPath<Order, Double> salary = Path.of(Order::getSalary);
        query().select(salary.avg()).list();
        assertThat(((ValueSelection) backend.last().selection()).items())
                .containsExactly(new AggregateProjection(AggregateFunction.AVG, path("salary")));
    }

    // ------------------------------------------------ not / notBetween（3、9）

    @Test
    @DisplayName("not()：落 Logical(NOT, [原条件])")
    void not() {
        query().where(Path.of(Order::getActive).eq(true).not()).list();

        Spec inner = new Comparison(path("active"), ComparisonOperator.EQ, Literal.of(true));
        assertThat(where()).isEqualTo(new Logical(LogicalOperator.NOT, List.of(inner)));
    }

    @Test
    @DisplayName("notBetween：落 Logical(NOT, [Between])")
    void notBetween() {
        query().where(Order::getScore).notBetween(1, 9).list();

        Spec inner = new Between(path("score"), Literal.of(1), Literal.of(9));
        assertThat(where()).isEqualTo(new Logical(LogicalOperator.NOT, List.of(inner)));
    }

    // --------------------------------------------- n 元 and/or（能力 4）

    @Test
    @DisplayName("n 元 and：一个 AND 节点带 n+1 个操作数，不拍平、不改语义")
    void naryAnd() {
        Predicate<Order> head = Path.of(Order::getActive).eq(true);
        Predicate<Order> second = Path.of(Order::getOrgId).eq(1L);
        Predicate<Order> third = Path.of(Order::getStatus).eq("PAID");

        query().where(head.and(List.of(second, third))).list();

        Logical and = (Logical) where();
        assertThat(and.operator()).isEqualTo(LogicalOperator.AND);
        assertThat(and.operands()).hasSize(3);
    }

    @Test
    @DisplayName("n 元 or：一个 OR 节点带 n+1 个操作数")
    void naryOr() {
        Predicate<Order> head = Path.of(Order::getActive).eq(true);
        Predicate<Order> second = Path.of(Order::getOrgId).eq(1L);

        query().where(head.or(List.of(second))).list();

        Logical or = (Logical) where();
        assertThat(or.operator()).isEqualTo(LogicalOperator.OR);
        assertThat(or.operands()).hasSize(2);
    }

    @Test
    @DisplayName("空集合：and(空) / or(空) 都只剩接收者（不放大成 TRUE/FALSE）")
    void emptyCollectionKeepsReceiver() {
        Predicate<Order> head = Path.of(Order::getStatus).eq("NEW");
        Spec receiver = new Comparison(path("status"), ComparisonOperator.EQ, Literal.of("NEW"));

        query().where(head.and(List.of())).list();
        assertThat(where()).isEqualTo(receiver);

        query().where(head.or(List.of())).list();
        assertThat(where()).isEqualTo(receiver);
    }

    @Test
    @DisplayName("恒真占位取反 → 恒假（NOT TRUE = FALSE，不是\"忽略条件\"）")
    void notOnAlwaysTruePlaceholder() {
        query().where(Path.of(Order::getStatus).eqIfNotNull(null).not()).list();

        assertThat(where()).isEqualTo(Constant.FALSE);
    }

    // ------------------------------------------------------ having（能力 7）

    @Test
    @DisplayName("having：条件落在 QuerySpec.having，不进 where")
    void having() {
        query().select(Path.of(Order::getOrgId), Path.of(Order::getId).count())
                .groupBy(Order::getOrgId)
                .having(Path.of(Order::getId).count().gt(1L))
                .list();

        QuerySpec spec = backend.last();
        assertThat(spec.where()).isNull();
        Operand count = new AggregateOperand(AggregateFunction.COUNT, path("id"));
        assertThat(spec.having()).isEqualTo(new Comparison(count, ComparisonOperator.GT, Literal.of(1L)));
        assertThat(spec.groupBy()).containsExactly(path("orgId"));
    }

    @Test
    @DisplayName("having 多次调用按 AND 累加")
    void havingAccumulates() {
        query().select(Path.of(Order::getOrgId), Path.of(Order::getId).count())
                .groupBy(Order::getOrgId)
                .having(Path.of(Order::getId).count().gt(1L))
                .having(Path.of(Order::getSalary).max().lt(100.0))
                .list();

        Logical having = (Logical) backend.last().having();
        assertThat(having.operator()).isEqualTo(LogicalOperator.AND);
        assertThat(having.operands()).hasSize(2);
    }

    @Test
    @DisplayName("having 与 count/exists 并存：forCount/forExists 保留 having")
    void countKeepsHaving() {
        backend.total = 3L;
        query().select(Path.of(Order::getOrgId), Path.of(Order::getId).count())
                .groupBy(Order::getOrgId)
                .having(Path.of(Order::getId).count().gt(1L))
                .count();

        assertThat(backend.last().having()).isNotNull();
        assertThat(backend.last().orderBy()).isEmpty();
    }

    // -------------------------------------------------- 多列 groupBy（能力 6）

    @Test
    @DisplayName("groupBy(多列)：按实参顺序累积，不替换")
    @SuppressWarnings("unchecked") // select 的变长参数在接口上是 abstract，调用点必然产生 unchecked 提示
    void multiColumnGroupBy() {
        query().select(
                        Path.of(Order::getOrgId),
                        Path.of(Order::getStatus),
                        Path.of(Order::getId).count())
                .groupBy(Order::getOrgId, Order::getStatus)
                .list();

        assertThat(backend.last().groupBy()).containsExactly(path("orgId"), path("status"));
    }

    // --------------------------------------------------- 多列投影（能力 5）

    @Test
    @DisplayName("三列投影：装配成 Tuple3，具名访问器带类型、按位置取值一致")
    void threeColumnProjection() {
        backend.rows = List.of((Object) new Object[] {1L, "PAID", BigDecimal.TEN});

        List<Tuple3<Long, String, BigDecimal>> rows = query().select(
                        Path.of(Order::getId), Path.of(Order::getStatus), Path.of(Order::getAmount))
                .list();

        assertThat(rows).hasSize(1);
        Tuple3<Long, String, BigDecimal> row = rows.getFirst();
        assertThat(row.size()).isEqualTo(3);
        assertThat(row.get0()).isEqualTo(1L);
        assertThat(row.get1()).isEqualTo("PAID");
        assertThat(row.get2()).isEqualTo(BigDecimal.TEN);
        assertThat(row.get(2)).isEqualTo(row.get2());
    }

    @Test
    @DisplayName("四列投影：由 ProjectionMappers 产出映射器，结果为带类型的 Tuple4")
    void fourColumnProjection() {
        backend.rows = List.of((Object) new Object[] {1L, 2L, 3.5d, 7});

        List<Tuple4<Long, Long, Double, Integer>> rows = query().project(ProjectionMappers.select(
                        Path.of(Order::getId),
                        Path.of(Order::getOrgId),
                        Path.of(Order::getBaseSalary),
                        Path.of(Order::getScore)))
                .list();

        assertThat(rows).hasSize(1);
        Tuple4<Long, Long, Double, Integer> row = rows.getFirst();
        assertThat(row.size()).isEqualTo(4);
        assertThat(row.get0()).isEqualTo(1L);
        assertThat(row.get2()).isEqualTo(3.5d);
        assertThat(row.get3()).isEqualTo(7);
        assertThat(row.get(3)).isEqualTo(row.get3());
    }

    @Test
    @DisplayName("两列投影仍是 Tuple2，且也是 Tuple（按位置取值一致）")
    void twoColumnProjectionIsTuple2() {
        backend.rows = List.of((Object) new Object[] {1L, 2L});

        List<Tuple2<Long, Long>> rows =
                query().select(Path.of(Order::getId), Path.of(Order::getOrgId)).list();

        Tuple2<Long, Long> tuple = rows.getFirst();
        assertThat(tuple.get0()).isEqualTo(1L);
        assertThat(tuple.get1()).isEqualTo(2L);
        assertThat(tuple.get(0)).isEqualTo(1L);
        assertThat(tuple.size()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- fixture

    private static AttributePath path(String name) {
        return AttributePath.of(Order.class, name);
    }

    private static EntityType<Order> meta() {
        EntityAttribute id = new EntityAttribute() {
            @Override
            public String name() {
                return "id";
            }

            @Override
            public Class<?> type() {
                return Long.class;
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
        return new EntityType<>() {
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

    @SuppressWarnings("unused")
    private static final class Order {

        private Long id;
        private Long orgId;
        private String status;
        private String name;
        private Boolean active;
        private Integer score;
        private Double salary;
        private Double baseSalary;
        private BigDecimal amount;

        public Long getId() {
            return id;
        }

        public Long getOrgId() {
            return orgId;
        }

        public String getStatus() {
            return status;
        }

        public String getName() {
            return name;
        }

        public Boolean getActive() {
            return active;
        }

        public Integer getScore() {
            return score;
        }

        public Double getSalary() {
            return salary;
        }

        public Double getBaseSalary() {
            return baseSalary;
        }

        public BigDecimal getAmount() {
            return amount;
        }
    }
}
