package io.github.nextentity.core.builder;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.core.compile.Compiler;
import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.core.meta.EntityAttribute;
import io.github.nextentity.core.meta.EntityType;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Between;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.In;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.NullCheck;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.Spec;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 算子全集的映射断言：`where(...)` 之后每个算子都落到一个确定的 Spec 节点，
/// 且 `*IfNotNull`/`*IfNotEmpty` 的 null/空串一律"不产生节点"。
@DisplayName("算子 → Spec")
class OperatorMappingTest {

    private final Recorder backend = new Recorder();

    private EntityQuery<Order> query() {
        return new QueryBuilder<>(meta(), backend, backend);
    }

    @Test
    @DisplayName("通用算子的每个方法都产生对应节点")
    void genericOperators() {
        query().where(Order::getStatus).ne("x").list();
        assertThat(where()).isEqualTo(new Comparison(path("status"), ComparisonOperator.NE, Literal.of("x")));

        query().where(Order::getAge).ge(1).list();
        assertThat(where()).isEqualTo(new Comparison(path("age"), ComparisonOperator.GE, Literal.of(1)));

        query().where(Order::getAge).le(9).list();
        assertThat(where()).isEqualTo(new Comparison(path("age"), ComparisonOperator.LE, Literal.of(9)));

        query().where(Order::getAge).lt(9).list();
        assertThat(where()).isEqualTo(new Comparison(path("age"), ComparisonOperator.LT, Literal.of(9)));

        query().where(Order::getAge).gt(9).list();
        assertThat(where()).isEqualTo(new Comparison(path("age"), ComparisonOperator.GT, Literal.of(9)));

        query().where(Order::getStatus).isNull().list();
        assertThat(where()).isEqualTo(new NullCheck(path("status"), false));

        query().where(Order::getStatus).isNotNull().list();
        assertThat(where()).isEqualTo(new NullCheck(path("status"), true));

        query().where(Order::getAge).between(1, 9).list();
        assertThat(where()).isEqualTo(new Between(path("age"), Literal.of(1), Literal.of(9)));
    }

    @Test
    @DisplayName("通用算子的 *IfNotNull：值为 null 时不产生节点")
    void genericIfNotNullSkips() {
        query().where(Order::getAge).geIfNotNull(null).list();
        assertThat(where()).isNull();

        query().where(Order::getAge).gtIfNotNull(null).list();
        assertThat(where()).isNull();

        query().where(Order::getAge).leIfNotNull(null).list();
        assertThat(where()).isNull();

        query().where(Order::getAge).ltIfNotNull(null).list();
        assertThat(where()).isNull();

        query().where(Order::getId).inIfNotNull(null).list();
        assertThat(where()).isNull();
    }

    @Test
    @DisplayName("通用算子的 *IfNotNull：有值时正常产生节点")
    void genericIfNotNullKeeps() {
        query().where(Order::getAge).gtIfNotNull(3).list();
        assertThat(where()).isEqualTo(new Comparison(path("age"), ComparisonOperator.GT, Literal.of(3)));

        query().where(Order::getId).inIfNotNull(List.of(1L)).list();
        assertThat(where()).isEqualTo(new In(path("id"), List.of(1L), false));
    }

    @Test
    @DisplayName("集合算子的两个重载（集合 / 变长参数）映射一致")
    void membershipOverloads() {
        query().where(Order::getId).in(List.of(1L, 2L)).list();
        Spec inCollection = where();

        query().where(Order::getId).in(1L, 2L).list();
        assertThat(where()).isEqualTo(inCollection);

        query().where(Order::getId).notIn(List.of(1L)).list();
        Spec notInCollection = where();

        query().where(Order::getId).notIn(1L).list();
        assertThat(where()).isEqualTo(notInCollection);
        assertThat(notInCollection).isEqualTo(new In(path("id"), List.of(1L), true));
    }

    @Test
    @DisplayName("字符串算子：eq/eqIfNotEmpty/equals 前缀短路与 contains 系列")
    void stringOperators() {
        query().where(Order::getName).eq("a").list();
        assertThat(where()).isEqualTo(new Comparison(path("name"), ComparisonOperator.EQ, Literal.of("a")));

        query().where(Order::getName).eqIfNotEmpty("a").list();
        assertThat(where()).isEqualTo(new Comparison(path("name"), ComparisonOperator.EQ, Literal.of("a")));

        query().where(Order::getName).eqIfNotEmpty("").list();
        assertThat(where()).isNull();

        query().where(Order::getName).eqIfNotNull(null).list();
        assertThat(where()).isNull();

        query().where(Order::getName).containsIfNotNull(null).list();
        assertThat(where()).isNull();

        query().where(Order::getName).containsIfNotNull("b").list();
        assertThat(where()).isEqualTo(new Comparison(path("name"), ComparisonOperator.LIKE, Literal.of("%b%")));

        query().where(Order::getName).containsIfNotEmpty("  ").list();
        assertThat(where()).isEqualTo(new Comparison(path("name"), ComparisonOperator.LIKE, Literal.of("%  %")));
    }

    @Test
    @DisplayName("字符串算子的空值与集合分支")
    void stringNullAndMembership() {
        query().where(Order::getName).isNull().list();
        assertThat(where()).isEqualTo(new NullCheck(path("name"), false));

        query().where(Order::getName).isNotNull().list();
        assertThat(where()).isEqualTo(new NullCheck(path("name"), true));

        query().where(Order::getName).in(List.of("a")).list();
        assertThat(where()).isEqualTo(new In(path("name"), List.of("a"), false));

        query().where(Order::getName).in("a", "b").list();
        assertThat(where()).isEqualTo(new In(path("name"), List.of("a", "b"), false));

        query().where(Order::getName).notIn(List.of("a")).list();
        assertThat(where()).isEqualTo(new In(path("name"), List.of("a"), true));

        query().where(Order::getName).notIn("a").list();
        assertThat(where()).isEqualTo(new In(path("name"), List.of("a"), true));

        query().where(Order::getName).inIfNotNull(List.of("a")).list();
        assertThat(where()).isEqualTo(new In(path("name"), List.of("a"), false));
    }

    // ---------------------------------------------------------------- fixture

    private Spec where() {
        return backend.last().where();
    }

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
        private String name;
        private String status;
        private Integer age;

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getStatus() {
            return status;
        }

        public Integer getAge() {
            return age;
        }
    }
}
