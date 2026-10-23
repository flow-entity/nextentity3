package io.github.nextentity.core.builder;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.core.compile.Compiler;
import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.core.meta.EntityAttribute;
import io.github.nextentity.core.meta.EntityType;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.In;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.Spec;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `StringStepOperator` 的短路面：`inIfNotNull(null)` 与 `isEmpty` 的两条判据路径。
///
/// `isEmpty` 的判据是 `value == null || value.isEmpty()`，两条路径分别由
/// `eqIfNotEmpty(null)`（`null` 侧）与 `eqIfNotEmpty("")`（`isEmpty()` 侧）走到；
/// `containsIfNotEmpty(null)` 是第二个调用 `isEmpty` 的算子，同样钉住。
///
/// 每个短路用例都配一条正向用例断言到节点：只测「跳过」时，把短路条件改成恒真也能通过，
/// 那样 `*IfNotEmpty("abc")` 会静默丢掉条件。守护的行（见 `StringStepOperator.java`）：
/// `:85` `inIfNotNull(Collection)`、`:114` `isEmpty(String)`。
@DisplayName("StringStepOperator：空值/空串短路与 in 集合")
class StringStepOperatorTest {

    private final Recorder backend = new Recorder();

    private EntityQuery<Order> query() {
        return new QueryBuilder<>(meta(), backend, backend);
    }

    // ------------------------------------------------ inIfNotNull（:85）

    @Test
    @DisplayName("inIfNotNull(null)：集合为 null 时跳过条件，WHERE 为空")
    void inIfNotNullSkipsNull() {
        query().where(Order::getName).inIfNotNull(null).list();

        assertThat(where()).isNull();
    }

    @Test
    @DisplayName("inIfNotNull(非空集合)：值被复制进 In 节点，顺序不变")
    void inIfNotNullKeepsValues() {
        query().where(Order::getName).inIfNotNull(List.of("PAID", "CREATED")).list();

        assertThat(where()).isEqualTo(new In(path("name"), List.of("PAID", "CREATED"), false));
    }

    // --------------------------------------- isEmpty 的两条路径（:114 与 :44、:94）

    @Test
    @DisplayName("eqIfNotEmpty(null)：isEmpty 的 null 侧命中，条件被跳过")
    void eqIfNotEmptySkipsNull() {
        query().where(Order::getName).eqIfNotEmpty(null).list();

        assertThat(where()).isNull();
    }

    @Test
    @DisplayName("eqIfNotEmpty(\"\")：isEmpty 的空串侧命中，条件被跳过")
    void eqIfNotEmptySkipsEmpty() {
        query().where(Order::getName).eqIfNotEmpty("").list();

        assertThat(where()).isNull();
    }

    @Test
    @DisplayName("eqIfNotEmpty(非空串)：条件照常产生，值原样进 Literal")
    void eqIfNotEmptyKeepsValue() {
        query().where(Order::getName).eqIfNotEmpty("PAID").list();

        assertThat(where()).isEqualTo(new Comparison(path("name"), ComparisonOperator.EQ, Literal.of("PAID")));
    }

    @Test
    @DisplayName("containsIfNotEmpty(null)：第二个 isEmpty 调用方同样跳过条件")
    void containsIfNotEmptySkipsNull() {
        query().where(Order::getName).containsIfNotEmpty(null).list();

        assertThat(where()).isNull();
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

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }
}
