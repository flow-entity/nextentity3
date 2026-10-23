package io.github.nextentity.core.builder;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.Predicate;
import io.github.nextentity.core.compile.Compiler;
import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.core.meta.EntityAttribute;
import io.github.nextentity.core.meta.EntityType;
import io.github.nextentity.core.spec.AggregateFunction;
import io.github.nextentity.core.spec.AggregateOperand;
import io.github.nextentity.core.spec.ArithmeticOperand;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.BinaryOperator;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.Constant;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.Logical;
import io.github.nextentity.core.spec.LogicalOperator;
import io.github.nextentity.core.spec.Operand;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.Spec;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `OpInterpreter` 两个需要单独构造才会走到的分支：
///
/// - `operand(...)`（`:94` `:95`）：实参自身是 `Op`（算术值）时递归成 [ArithmeticOperand]，
///   而不是被 `Literal` 包一层——"右值是一列算出来的数"与"右值是一个常数"必须可区分；
/// - `logical(...)`（`:148` `:150` `:151` `:155`）：`and`/`or` 的子条件里混入
///   api 的恒真占位（`AlwaysTrue`，不实现 `Op`）时的折叠语义。恒真占位在 `AND` 下跳过，
///   在 `OR` 下让整条 `OR` 恒真（跳过会算错）；一个都不剩时 `AND` 恒真、`OR` 恒假。
///
/// 折叠结果必须断言到节点：恒真本身在 `where`/`having` 里表现为"没有条件"，
/// 单看 `where() == null` 分不清"折成恒真"与"解释器返回 null"，
/// 因此 `logical` 的前两个分支都放进更大的逻辑节点里观察。
@DisplayName("OpInterpreter：操作数递归与逻辑折叠")
class OpInterpreterTest {

    private final Recorder backend = new Recorder();

    private EntityQuery<Order> query() {
        return new QueryBuilder<>(meta(), backend, backend);
    }

    private Spec where() {
        return backend.last().where();
    }

    // ------------------------------------------------- 操作数递归（:94 `:95`）

    @Test
    @DisplayName("右操作数是算术表达式：递归成 ArithmeticOperand，不包成字面量")
    void interpretArithmeticOperand() {
        query().where(Order::getSalary)
                .gt(Path.of(Order::getBaseSalary).add(1000.0))
                .list();

        Operand expected = new ArithmeticOperand(BinaryOperator.ADD, path("baseSalary"), Literal.of(1000.0));
        assertThat(where()).isEqualTo(new Comparison(path("salary"), ComparisonOperator.GT, expected));
    }

    // ------------------------------------------------ 逻辑折叠（:148 - :155）

    @Test
    @DisplayName("having 的 and 里套 or：or 中的恒真占位使整条 or 折成恒真")
    void foldAlwaysTrueInsideHavingOr() {
        Predicate<Order> threshold = Path.of(Order::getId).count().gt(1L);
        Predicate<Order> either = Path.of(Order::getSalary)
                .max()
                .gt(100.0)
                .or(Path.of(Order::getStatus).eqIfNotNull(null));

        query().select(Path.of(Order::getOrgId), Path.of(Order::getId).count())
                .groupBy(Order::getOrgId)
                .having(threshold.and(either))
                .list();

        Logical having = (Logical) backend.last().having();
        assertThat(having.operator()).isEqualTo(LogicalOperator.AND);

        Operand count = new AggregateOperand(AggregateFunction.COUNT, path("id"));
        assertThat(having.operands())
                .containsExactly(new Comparison(count, ComparisonOperator.GT, Literal.of(1L)), Constant.TRUE);
    }

    @Test
    @DisplayName("and 的子条件全是恒真占位：折成恒真，取反后是 NOT TRUE")
    void foldAllPlaceholderAnd() {
        Predicate<Order> allShortCircuited = Path.of(Order::getStatus)
                .eqIfNotNull(null)
                .and(List.of(Path.of(Order::getAge).eqIfNotNull(null)));

        query().where(allShortCircuited).list();
        assertThat(where()).isNull();

        query().where(allShortCircuited.not()).list();
        assertThat(where()).isEqualTo(Logical.not(Constant.TRUE));
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

        @Override
        public QuerySpec compile(QuerySpec spec) {
            return spec;
        }

        @Override
        public <R> List<R> list(QuerySpec query) {
            specs.add(query);
            return List.of();
        }

        @Override
        public long count(QuerySpec query) {
            specs.add(query);
            return 0;
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
        private Integer age;
        private Double salary;
        private Double baseSalary;

        public Long getId() {
            return id;
        }

        public Long getOrgId() {
            return orgId;
        }

        public String getStatus() {
            return status;
        }

        public Integer getAge() {
            return age;
        }

        public Double getSalary() {
            return salary;
        }

        public Double getBaseSalary() {
            return baseSalary;
        }
    }
}
