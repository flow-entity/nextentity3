package io.github.nextentity.core.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.Op;
import io.github.nextentity.api.Operation;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.PathRef;
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
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `OpInterpreter` 三类需要单独构造才会走到的分支：
///
/// - `operand(...)`（`:94` `:95`）：实参自身是 `Op`（算术值）时递归成 [ArithmeticOperand]，
///   而不是被 `Literal` 包一层——"右值是一列算出来的数"与"右值是一个常数"必须可区分；
/// - `logical(...)`（`:148` `:150` `:151` `:155`）：`and`/`or` 的子条件里混入
///   api 的恒真占位（`AlwaysTrue`，不实现 `Op`）时的折叠语义。恒真占位在 `AND` 下跳过，
///   在 `OR` 下让整条 `OR` 恒真（跳过会算错）；一个都不剩时 `AND` 恒真、`OR` 恒假。
/// - 三个 `switch` 的**拒绝分支**（`:62` `:81` `:124`）：它们是穷尽性检查的结果，不是兜底，
///   公开调用点进不去，只能手工构造 `Op` 才走得到，见本文件末尾那组用例。
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

    // ----------------------------------------- 穷尽性检查带来的拒绝分支（:62 `:81` `:124` `:155`）

    /// `where`/`having` 只接受 `Expression<T, Boolean>`，聚合值不是谓词，公开调用点走不到这条拒绝分支。
    ///
    /// 钉它的理由：api 的 `Operation` 一旦新增聚合常量，这个 `switch` 会编译失败，
    /// 补分支的人必须显式决定"新常量能不能当条件"。若有人图省事把拒绝改成"也落成 Comparison"，
    /// `max(x)` 会被当成某条属性路径去比较——本用例让这种改动立刻变红。
    @Test
    @DisplayName("toSpec 拒绝聚合运算：MAX 不是条件运算")
    void rejectAggregateAsCondition() {
        Op aggregate = (Op) Path.of(Order::getSalary).max();

        assertThatThrownBy(() -> OpInterpreter.toSpec(aggregate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("不是条件运算: MAX");
    }

    /// 投影只来自 `select(...)`，比较谓词进不去，公开调用点走不到这条拒绝分支。
    ///
    /// 钉它的理由：`toProjection` 的入参类型是 `Op` 而不是更窄的类型，靠这个 `switch` 守住
    /// "条件节点不是投影项"。少了它，条件被当作一列渲染出去，SQL 才炸。
    @Test
    @DisplayName("toProjection 拒绝条件运算：EQ 不是投影运算")
    void rejectConditionAsProjection() {
        Op condition = (Op) Path.of(Order::getAge).eq(3);

        assertThatThrownBy(() -> OpInterpreter.toProjection(condition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("不是投影运算: EQ");
    }

    /// 需要"主语本身是比较谓词"的 `Op`。api 里只有 `Leaf` 带 `subject`，而 `Leaf` 的 `subject`
    /// 只由聚合值/算术值传入（两者传的都是 `this`），公开 API 造不出"主语是 EQ 谓词"的形态；
    /// `Op` 又是 sealed（`permits Leaf, AbstractAggregate, AbstractArithmetic`，三者不可见或不可继承），
    /// 匿名实现编译不过，因此这里反射调用 api record 的规范构造器。
    ///
    /// 钉它的理由：`left(...)` 一旦改成"有 subject 就当操作数用"，`max(x) > 5` 与
    /// `x > 5` 就会被解释成同一个节点；这条分支是那条分界线的唯一守卫。
    @Test
    @DisplayName("value 拒绝条件主语：主语是比较谓词时不是值表达式")
    void rejectConditionAsValue() {
        Op subject = leaf(Operation.EQ, Path.of(Order::getAge).refs(), List.of(3), null);
        Op outer = leaf(Operation.GT, Path.of(Order::getAge).refs(), List.of(5), subject);

        assertThatThrownBy(() -> OpInterpreter.toSpec(outer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("不是值表达式: EQ");
    }

    /// 空操作数的 `AND`/`OR` 在 api 侧被短路成"返回自身"（`and(空集合)` 返回 `this`），
    /// 解释器的 `operands.isEmpty()` 分支因此只能手工构造才走得到。
    ///
    /// 钉它的理由：这是空集合语义的唯一出口——`OR` 空 = 恒假（匹配 0 行）、`AND` 空 = 恒真（不过滤）。
    /// 两者写反会让 `or(空集合)` 匹配全表，且从 `where() == null` 上完全看不出来。
    @Test
    @DisplayName("logical 的空操作数：OR 恒假、AND 恒真")
    void foldEmptyLogical() {
        Op emptyOr = leaf(Operation.OR, List.of(), List.of(), null);
        Op emptyAnd = leaf(Operation.AND, List.of(), List.of(), null);

        assertThat(OpInterpreter.toSpec(emptyOr)).isEqualTo(Constant.FALSE);
        assertThat(OpInterpreter.toSpec(emptyAnd)).isEqualTo(Constant.TRUE);
    }

    /// 手工构造 api 的 `Op` 线格式：反射调用 `io.github.nextentity.api.Conditions.Leaf` 的规范构造器。
    ///
    /// `Leaf` 是包私有的 record，所在文件不可见；`Op` 的另外两个被允许的实现同理，
    /// 因此这是构造"公开 API 造不出来的线格式"的唯一路径。它只出现在拒绝分支的用例里，
    /// 正常路径一律从 `Path.of(...)` 走公开入口。
    private static Op leaf(Operation operation, List<PathRef<?, ?>> refs, List<Object> arguments, Op subject) {
        try {
            // 类名由 api 的公开包名拼出：Conditions 本身不可见，写不成 Class 字面量
            Constructor<?> canonical = Arrays.stream(Class.forName(Path.class.getPackageName() + ".Conditions$Leaf")
                            .getDeclaredConstructors())
                    .filter(candidate -> candidate.getParameterCount() == 4)
                    .findFirst()
                    .orElseThrow();
            canonical.setAccessible(true);
            return (Op) canonical.newInstance(operation, refs, arguments, subject);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("无法构造 api.Conditions.Leaf：Op 是 sealed，手工线格式只能走反射", e);
        }
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
