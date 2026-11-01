package io.github.nextentity.core.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.core.compile.Compiler;
import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.core.meta.EntityAttribute;
import io.github.nextentity.core.meta.EntityType;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.Spec;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 交叉验证（R3 独立复现）：`contains(null)` 必须 fail-fast，而不是拼出 `LIKE '%null%'`。
///
/// 判据是**契约一致性**，三条同时成立才算缺陷：
/// 1. `api/StringOps#contains` javadoc 写着 `@param value 子串，非 null`；
/// 2. 同族其余入口对 `null` 都已 fail-fast（`like`/`eq` 经 `Literal`、`notContains`/`startsWith`/
///    `endsWith` 经 `StringPatternOps` 的 `requireNonNull`）；
/// 3. 静默 `%null%` 的后果是「答错」而不是「报错」——查的是字面量 `null` 子串，调用方拿到空集。
///
/// 两条入口都要实测：
/// - 查询链入口 `query.where(X::getName).contains(null)` → `StringStepOperator#contains` → `Specs#contains`；
/// - api `Op` 形态入口 `EntityPath.of(...).get(...).contains(null)` → `PathValues.StringValue#contains`
///   → `Conditions#stringOp` → `Leaf` 的 `List.copyOf(arguments)`。
///
/// 绿对照必须一并钉住，否则修 `contains` 时容易把 `*IfNotNull` / `*IfNotEmpty` 的短路语义一起改掉：
/// 这两个变体在 `null` 时是「不加这条条件」，不是「匹配 `null` 行」，也不是「抛异常」。
@DisplayName("contains(null)：必须 fail-fast，短路变体必须保持不变")
class ContainsNullContractTest {

    private final Recorder backend = new Recorder();

    private EntityQuery<Order> query() {
        return new QueryBuilder<>(meta(), backend, backend);
    }

    // ---------------------------------------------------------------- 红：缺陷本身

    @Test
    @DisplayName("where(X::getName).contains(null)：必须抛 NPE，不得拼出 %null%")
    void chainContainsNullIsRejected() {
        assertThatThrownBy(() -> query().where(Order::getName).contains(null).list())
                .as("`contains(null)` 只允许「拒绝」或「短路」，不允许把 null 拼进 LIKE 模式串")
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("where(X::getName).lower().contains(null)：函数主语上同样必须拒绝")
    void chainContainsNullOnFunctionSubjectIsRejected() {
        assertThatThrownBy(() ->
                        query().where(Order::getName).lower().contains(null).list())
                .isInstanceOf(NullPointerException.class);
    }

    // ------------------------------------------------- 对照：api 的 Op 形态入口本来就会拒绝

    @Test
    @DisplayName("api StringPath.contains(null)：Op 形态入口抛 NPE（与链式入口必须同结论）")
    void apiStringPathContainsNullIsRejected() {
        StringPath<Order> name = EntityPath.of(Order::getCustomer).get(Customer::getName);

        assertThatThrownBy(() -> name.contains(null)).isInstanceOf(NullPointerException.class);
    }

    // ------------------------------------------- 对照：短路变体在 null 时不加条件、不抛异常

    @Test
    @DisplayName("containsIfNotNull(null)：短路成「不加这条条件」")
    void containsIfNotNullSkipsNull() {
        query().where(Order::getName).containsIfNotNull(null).list();

        assertThat(where()).isNull();
    }

    @Test
    @DisplayName("containsIfNotEmpty(null)：短路成「不加这条条件」")
    void containsIfNotEmptySkipsNull() {
        query().where(Order::getName).containsIfNotEmpty(null).list();

        assertThat(where()).isNull();
    }

    @Test
    @DisplayName("containsIfNotEmpty(\"\")：空串同样短路")
    void containsIfNotEmptySkipsEmpty() {
        query().where(Order::getName).containsIfNotEmpty("").list();

        assertThat(where()).isNull();
    }

    // ------------------------------- 对照：非 null（含空串）照旧，拒绝不能扩大成「不许空串」

    @Test
    @DisplayName("contains(\"\")：空串合法，命中所有非 null 值（LIKE '%%'）")
    void containsEmptyStringIsAllowed() {
        query().where(Order::getName).contains("").list();

        assertThat(where()).isEqualTo(new Comparison(path("name"), ComparisonOperator.LIKE, Literal.of("%%")));
    }

    @Test
    @DisplayName("contains(\"ann\")：正常拼成 %ann%")
    void containsWrapsValue() {
        query().where(Order::getName).contains("ann").list();

        assertThat(where()).isEqualTo(new Comparison(path("name"), ComparisonOperator.LIKE, Literal.of("%ann%")));
    }

    // ------------------------------- 对照：同族其余入口对 null 都已 fail-fast（缺陷的分叉证据）

    @Test
    @DisplayName("同族算子对 null 全部 NPE：contains 不得是唯一静默拼串的那个")
    void siblingOperatorsRejectNull() {
        assertThatThrownBy(() -> query().where(Order::getName).like(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> query().where(Order::getName).notLike(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> query().where(Order::getName).startsWith(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> query().where(Order::getName).endsWith(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> query().where(Order::getName).notContains(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> query().where(Order::getName).eq(null)).isInstanceOf(NullPointerException.class);
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
        private Customer customer;

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public Customer getCustomer() {
            return customer;
        }
    }

    @SuppressWarnings("unused")
    private static final class Customer {

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
