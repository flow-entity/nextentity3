package io.github.nextentity.core.repo;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.Path;
import io.github.nextentity.core.compile.Compiler;
import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.core.exec.PersistExecutor;
import io.github.nextentity.core.meta.EntityAttribute;
import io.github.nextentity.core.meta.EntityType;
import io.github.nextentity.core.spec.AggregateFunction;
import io.github.nextentity.core.spec.AggregateProjection;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.ValueSelection;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 装配器测试：四个零件拼成 `EntityOperations`，查询走构建器、写入转给后端。
@DisplayName("SimpleEntityOperationsFactory")
class SimpleEntityOperationsFactoryTest {

    private final Recorder recorder = new Recorder();
    private final List<String> writes = new ArrayList<>();

    private SimpleEntityOperationsFactory<QuerySpec> factory() {
        return new SimpleEntityOperationsFactory<>(ignore -> meta(), recorder, recorder, ignore -> persist());
    }

    @Test
    @DisplayName("operations() 返回该实体的操作入口，元数据透传")
    void operationsCarriesMeta() {
        EntityOperations<Order> operations = factory().operations(Order.class);

        assertThat(operations.entityType().type()).isEqualTo(Order.class);
        assertThat(operations.entityType().id().name()).isEqualTo("id");
        assertThat(operations.entityType().version()).isNull();
    }

    @Test
    @DisplayName("query() 每次返回独立构建器：链式状态互不影响")
    void queryIsFreshPerCall() {
        EntityOperations<Order> operations = factory().operations(Order.class);
        EntityQuery<Order> first = operations.query();
        EntityQuery<Order> second = operations.query();

        assertThat(first).isNotSameAs(second);
        first.where(Order::getStatus).eq("A").list();
        second.list();

        assertThat(recorder.specs.getLast().where()).isNull();
        // `isNotNull()` 只证明「第一个构建器的 where 非空」，与第二个构建器的条件串味
        // （比如共用同一份可变规格，后写的条件覆盖前面的）分不出来。这里钉住第一个规格的
        // 条件本身：列、算子、字面量任一处被覆盖或丢失都会红。
        assertThat(recorder.specs.getFirst().where())
                .isEqualTo(new Comparison(
                        AttributePath.of(Order.class, "status"), ComparisonOperator.EQ, Literal.of("A")));
    }

    @Test
    @DisplayName("聚合投影：countDistinct 映射到聚合函数节点")
    void aggregateProjection() {
        EntityOperations<Order> operations = factory().operations(Order.class);
        operations.query().select(Path.of(Order::getId).countDistinct()).list();

        ValueSelection selection = (ValueSelection) recorder.specs.getLast().selection();
        assertThat(selection.items())
                .containsExactly(
                        new AggregateProjection(AggregateFunction.COUNT_DISTINCT, AttributePath.of(Order.class, "id")));
    }

    @Test
    @DisplayName("写方法直接转给后端写入执行器")
    void writesDelegate() {
        EntityOperations<Order> operations = factory().operations(Order.class);
        Order order = new Order();

        operations.insert(order);
        operations.insertAll(List.of(order));
        operations.update(order);
        operations.updateAll(List.of(order));

        assertThat(writes).containsExactly("insert", "insertAll", "update", "updateAll");
    }

    // 聚合链（`max().sum()`）由 api 的类型拒绝：聚合方法返回 `Expression`，其上没有聚合方法，
    // 所以本文件不需要"聚合再聚合"的运行期用例。

    // ---------------------------------------------------------------- fixture

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
                return null;
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

    private PersistExecutor<Order> persist() {
        return new PersistExecutor<>() {
            @Override
            public void insert(Order entity) {
                writes.add("insert");
            }

            @Override
            public void insertAll(Iterable<Order> entities) {
                writes.add("insertAll");
            }

            @Override
            public void update(Order entity) {
                writes.add("update");
            }

            @Override
            public void updateAll(Iterable<Order> entities) {
                writes.add("updateAll");
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
    }

    @SuppressWarnings("unused")
    static final class Order {

        private Long id;
        private String status;

        public Long getId() {
            return id;
        }

        public String getStatus() {
            return status;
        }
    }
}
