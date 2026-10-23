package io.github.nextentity.core.builder;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.Path;
import io.github.nextentity.core.compile.Compiler;
import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.core.meta.EntityAttribute;
import io.github.nextentity.core.meta.EntityType;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Between;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.NullCheck;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.Spec;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/// `PathStepOperator` 的两条能力面：列对列比较、NumberRef 上的值/空值算子。
///
/// 「列对列」与「列对值」在 AST 上的唯一区别是**右操作数的类型**：
/// `where(X::getAge).ge(Path.of(X::getScore))` 的右值是 [AttributePath]，
/// `where(X::getAge).ge(1)` 的右值是 [Literal]。因此每条用例都断言到节点结构，
/// 而不是只断言"没抛异常"——右值退化成字面量正是要挡住的错误。
///
/// 守护的重载（行号见 `PathStepOperator.java`）：
/// - `:48` `eqIfNotNull(U value)`：NumberRef 路径（既有用例只走 StringRef）
/// - `:53` `ne(U value)`：NumberRef 路径（既有用例只走 StringRef）
/// - `:58` `:68` `:78` `:88` `:98` `ne/ge/gt/le/lt(Expression<T,U>)`：列对列比较
/// - `:104` `:114` `:119` `geIfNotNull/leIfNotNull/ltIfNotNull(U value)`：`null` 侧的短路
///   （既有用例只走非 `null` 侧）
/// - `:123` `:128` `isNull()` / `isNotNull()`：NumberRef 路径
/// - `:165` `between(Expression<T,U>, Expression<T,U>)`：列对列区间
@DisplayName("PathStepOperator：列对列与 NumberRef 路径")
class PathStepOperatorTest {

    private final Recorder backend = new Recorder();

    private EntityQuery<Order> query() {
        return new QueryBuilder<>(meta(), backend, backend);
    }

    // ------------------------------------------------ 列对列比较（:58 - :98）

    /// 列对列比较的 5 个重载共用一条参数化用例：右值必须是 [AttributePath]。
    @ParameterizedTest(name = "{0}")
    @MethodSource("columnOperators")
    @DisplayName("列对列比较：右值落成 AttributePath，不是 Literal")
    void compareColumnWithColumn(String name, ComparisonOperator expected, ColumnOperator operator) {
        operator.apply(query());

        Comparison comparison = (Comparison) where();
        assertThat(comparison.operator()).isEqualTo(expected);
        assertThat(comparison.left()).isEqualTo(path("age"));
        assertThat(comparison.right()).isEqualTo(path("score"));
        assertThat(comparison.right()).isInstanceOf(AttributePath.class);
    }

    @Test
    @DisplayName("列对列区间：between 的两端都是属性路径")
    void compareColumnsBetween() {
        query().where(Order::getAge)
                .between(Path.of(Order::getScore), Path.of(Order::getLevel))
                .list();

        assertThat(where()).isEqualTo(new Between(path("age"), path("score"), path("level")));
    }

    // ------------------------------------------- NumberRef 的值/空值算子（:48、:53、:123、:128）

    @Test
    @DisplayName("NumberRef 的值版本 ne：右值是字面量")
    void compareNumberPathWithValue() {
        query().where(Order::getAge).ne(3).list();

        Comparison comparison = (Comparison) where();
        assertThat(comparison).isEqualTo(new Comparison(path("age"), ComparisonOperator.NE, Literal.of(3)));
        assertThat(comparison.right()).isInstanceOf(Literal.class);
    }

    @Test
    @DisplayName("NumberRef 的 eqIfNotNull：有值产生比较，null 一个节点都不产生")
    void compareNumberPathIfNotNull() {
        query().where(Order::getAge).eqIfNotNull(3).list();
        assertThat(where()).isEqualTo(new Comparison(path("age"), ComparisonOperator.EQ, Literal.of(3)));

        query().where(Order::getAge).eqIfNotNull(null).list();
        assertThat(where()).isNull();
    }

    @Test
    @DisplayName("NumberRef 的 geIfNotNull：有值产生 GE 比较，null 一个节点都不产生")
    void geIfNotNullSkipsNull() {
        query().where(Order::getAge).geIfNotNull(18).list();
        assertThat(where()).isEqualTo(new Comparison(path("age"), ComparisonOperator.GE, Literal.of(18)));

        query().where(Order::getAge).geIfNotNull(null).list();
        assertThat(where()).isNull();
    }

    @Test
    @DisplayName("NumberRef 的 leIfNotNull：有值产生 LE 比较，null 一个节点都不产生")
    void leIfNotNullSkipsNull() {
        query().where(Order::getAge).leIfNotNull(60).list();
        assertThat(where()).isEqualTo(new Comparison(path("age"), ComparisonOperator.LE, Literal.of(60)));

        query().where(Order::getAge).leIfNotNull(null).list();
        assertThat(where()).isNull();
    }

    @Test
    @DisplayName("NumberRef 的 ltIfNotNull：有值产生 LT 比较，null 一个节点都不产生")
    void ltIfNotNullSkipsNull() {
        query().where(Order::getAge).ltIfNotNull(60).list();
        assertThat(where()).isEqualTo(new Comparison(path("age"), ComparisonOperator.LT, Literal.of(60)));

        query().where(Order::getAge).ltIfNotNull(null).list();
        assertThat(where()).isNull();
    }

    @Test
    @DisplayName("NumberRef 的空值判断：isNull / isNotNull 落 NullCheck(false / true)")
    void nullCheckNumberPath() {
        query().where(Order::getAge).isNull().list();
        assertThat(where()).isEqualTo(new NullCheck(path("age"), false));

        query().where(Order::getAge).isNotNull().list();
        assertThat(where()).isEqualTo(new NullCheck(path("age"), true));
    }

    // ---------------------------------------------------------------- fixture

    /// 列对列算子的调用形态：跑完「算子 + 终止符」，条件留在 [Recorder] 里。
    @FunctionalInterface
    private interface ColumnOperator {

        void apply(EntityQuery<Order> query);
    }

    private static Stream<Arguments> columnOperators() {
        return Stream.of(
                Arguments.of("ne", ComparisonOperator.NE, (ColumnOperator) query ->
                        query.where(Order::getAge).ne(Path.of(Order::getScore)).list()),
                Arguments.of("ge", ComparisonOperator.GE, (ColumnOperator) query ->
                        query.where(Order::getAge).ge(Path.of(Order::getScore)).list()),
                Arguments.of("gt", ComparisonOperator.GT, (ColumnOperator) query ->
                        query.where(Order::getAge).gt(Path.of(Order::getScore)).list()),
                Arguments.of("le", ComparisonOperator.LE, (ColumnOperator) query ->
                        query.where(Order::getAge).le(Path.of(Order::getScore)).list()),
                Arguments.of("lt", ComparisonOperator.LT, (ColumnOperator) query ->
                        query.where(Order::getAge).lt(Path.of(Order::getScore)).list()));
    }

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
        private Integer age;
        private Integer score;
        private Integer level;

        public Long getId() {
            return id;
        }

        public Integer getAge() {
            return age;
        }

        public Integer getScore() {
            return score;
        }

        public Integer getLevel() {
            return level;
        }
    }
}
