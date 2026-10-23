package io.github.nextentity.core.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.AggregateValue;
import io.github.nextentity.api.EntityPath;
import io.github.nextentity.api.Expression;
import io.github.nextentity.api.NumberPath;
import io.github.nextentity.api.NumberValue;
import io.github.nextentity.api.Op;
import io.github.nextentity.api.Operation;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.PathOperator;
import io.github.nextentity.api.Predicate;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.api.model.Tuple;
import io.github.nextentity.api.model.Tuple2;
import io.github.nextentity.api.model.Tuple3;
import io.github.nextentity.api.model.Tuple4;
import io.github.nextentity.api.model.Tuple5;
import io.github.nextentity.api.model.Tuple6;
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
import io.github.nextentity.core.spec.In;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.Logical;
import io.github.nextentity.core.spec.LogicalOperator;
import io.github.nextentity.core.spec.NullCheck;
import io.github.nextentity.core.spec.Operand;
import io.github.nextentity.core.spec.Projection;
import io.github.nextentity.core.spec.Spec;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/// `nextentity-api` 的 DSL 组装层：`Path.of(...)` / `Predicate#or(PathRef)` 拼出的 `Op` 线格式，
/// 经 [OpInterpreter] 翻译后必须落成预期的 `core.spec` 节点。
///
/// **为什么这些 api 用例住在 `core` 的测试树**：`nextentity-api` 的 enforcer 规则
/// `enforce-api-is-dependency-free` 用 `<exclude>*:*</exclude>` 禁掉该模块的**一切**依赖
/// （test 作用域也在内），给 `api` 加 `junit-jupiter` 会让 `validate` 直接失败，因此 `api`
/// 不能有 `src/test`。而 `api` 的实现类全是包私有的值对象，唯一可观察的入口是
/// `Path#refs()` / `Op#operation()` 这组线格式，把它们读成节点的读取器就是同包可见的
/// [OpInterpreter]。于是断言放在第一个消费者 `nextentity-core` 的测试树：用 `api` 的公开
/// 入口构造，用 `core` 的解释器观察，两边都不需要新增依赖。
///
/// **oracle 形状**：`api` 只拼值不求值，它的可观测契约因此是**结构**而不是值——每条用例都把
/// 解释结果与手写的 `core.spec` 节点整体比对（record 相等），而不是断言"非空"。
/// 唯一无法整体比对的实参是 `Path` 值对象本身（`PathValues` 的类没有 `equals`），
/// 这类实参一律断言解析后的 [AttributePath]。
@DisplayName("api DSL 组装层：Path/Predicate 值 → core.spec 节点")
class ApiOperatorAssemblyTest {

    // ------------------------------------------------ PathValues.Value：比较 / 区间 / 集合 / 空值

    @Test
    @DisplayName("Value.eq：值版本落 Literal，表达式版本落 AttributePath")
    void valueEquality() {
        Path<Order, Integer> age = Path.of(Order::getAge);

        assertThat(spec(age.eq(3))).isEqualTo(new Comparison(path("age"), ComparisonOperator.EQ, Literal.of(3)));
        assertThat(spec(age.eq(Path.of(Order::getScore))))
                .isEqualTo(new Comparison(path("age"), ComparisonOperator.EQ, path("score")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("valueComparisons")
    @DisplayName("Value 的比较算子：值 → Literal，表达式 → AttributePath")
    void valueComparison(String name, Spec expected, Function<Path<Order, Integer>, Predicate<Order>> call) {
        assertThat(spec(call.apply(Path.of(Order::getAge)))).isEqualTo(expected);
    }

    @Test
    @DisplayName("Value 的 *IfNotNull：null 折成共享的恒真占位，非 null 按对应算子成节点")
    void valueIfNotNull() {
        Path<Order, Integer> age = Path.of(Order::getAge);

        assertThat(age.eqIfNotNull(null)).isNotInstanceOf(Op.class);
        assertThat(age.geIfNotNull(null)).isNotInstanceOf(Op.class);
        assertThat(age.gtIfNotNull(null)).isNotInstanceOf(Op.class);
        assertThat(age.leIfNotNull(null)).isNotInstanceOf(Op.class);
        assertThat(age.ltIfNotNull(null)).isNotInstanceOf(Op.class);
        assertThat(age.eqIfNotNull(null)).isSameAs(age.ltIfNotNull(null));

        assertThat(spec(age.eqIfNotNull(3)))
                .isEqualTo(new Comparison(path("age"), ComparisonOperator.EQ, Literal.of(3)));
        assertThat(spec(age.geIfNotNull(3)))
                .isEqualTo(new Comparison(path("age"), ComparisonOperator.GE, Literal.of(3)));
        assertThat(spec(age.gtIfNotNull(3)))
                .isEqualTo(new Comparison(path("age"), ComparisonOperator.GT, Literal.of(3)));
        assertThat(spec(age.leIfNotNull(3)))
                .isEqualTo(new Comparison(path("age"), ComparisonOperator.LE, Literal.of(3)));
        assertThat(spec(age.ltIfNotNull(3)))
                .isEqualTo(new Comparison(path("age"), ComparisonOperator.LT, Literal.of(3)));
    }

    @Test
    @DisplayName("Value 的空值判断与集合算子：落 NullCheck / In")
    void valueNullCheckAndMembership() {
        Path<Order, Integer> age = Path.of(Order::getAge);

        assertThat(spec(age.isNull())).isEqualTo(new NullCheck(path("age"), false));
        assertThat(spec(age.isNotNull())).isEqualTo(new NullCheck(path("age"), true));

        In expected = new In(path("age"), List.of(3, 5), false);
        assertThat(spec(age.in(List.of(3, 5)))).isEqualTo(expected);
        assertThat(spec(age.in(3, 5))).isEqualTo(expected);
        assertThat(spec(age.notIn(List.of(3, 5)))).isEqualTo(new In(path("age"), List.of(3, 5), true));
        assertThat(spec(age.notIn(3, 5))).isEqualTo(new In(path("age"), List.of(3, 5), true));

        assertThat(age.inIfNotNull(null)).isNotInstanceOf(Op.class);
        assertThat(spec(age.inIfNotNull(List.of(7)))).isEqualTo(new In(path("age"), List.of(7), false));
    }

    @Test
    @DisplayName("Value 的区间算子：notBetween 就是 between 取反，不是新运算符")
    void valueBetween() {
        Path<Order, Integer> age = Path.of(Order::getAge);
        Between between = new Between(path("age"), Literal.of(3), Literal.of(5));

        assertThat(spec(age.between(3, 5))).isEqualTo(between);
        assertThat(spec(age.between(Path.of(Order::getScore), Path.of(Order::getLevel))))
                .isEqualTo(new Between(path("age"), path("score"), path("level")));
        assertThat(spec(age.notBetween(3, 5))).isEqualTo(Logical.not(between));
    }

    @Test
    @DisplayName("Value 的四个聚合：投影落 AggregateProjection，路径取自 refs()")
    void valueAggregates() {
        Path<Order, String> status = Path.of(Order::getStatus);

        assertThat(projection(status.count()))
                .isEqualTo(new AggregateProjection(AggregateFunction.COUNT, path("status")));
        assertThat(projection(status.countDistinct()))
                .isEqualTo(new AggregateProjection(AggregateFunction.COUNT_DISTINCT, path("status")));
        assertThat(projection(status.max())).isEqualTo(new AggregateProjection(AggregateFunction.MAX, path("status")));
        assertThat(projection(status.min())).isEqualTo(new AggregateProjection(AggregateFunction.MIN, path("status")));
    }

    // ------------------------------------------------------------- PathValues.NumberRoot

    @Test
    @DisplayName("NumberRoot 的 sum/avg：投影落 AggregateProjection")
    void numberRootAggregates() {
        NumberPath<Order, Integer> age = Path.of(Order::getAge);

        assertThat(projection(age.sum())).isEqualTo(new AggregateProjection(AggregateFunction.SUM, path("age")));
        assertThat(projection(age.avg())).isEqualTo(new AggregateProjection(AggregateFunction.AVG, path("age")));
    }

    @Test
    @DisplayName("NumberRoot 的算术：左操作数就是 refs() 的路径（subject 为 null）")
    void numberRootArithmetic() {
        NumberPath<Order, Double> salary = Path.of(Order::getSalary);

        assertThat(((Op) salary.add(1000.0)).subject()).isNull();
        assertThat(projection(salary.add(1000.0)))
                .isEqualTo(new ComputedProjection(
                        new ArithmeticOperand(BinaryOperator.ADD, path("salary"), Literal.of(1000.0))));
        assertThat(projection(salary.sub(10.0)))
                .isEqualTo(new ComputedProjection(
                        new ArithmeticOperand(BinaryOperator.SUB, path("salary"), Literal.of(10.0))));
        assertThat(projection(salary.mul(12.0)))
                .isEqualTo(new ComputedProjection(
                        new ArithmeticOperand(BinaryOperator.MUL, path("salary"), Literal.of(12.0))));
        assertThat(projection(salary.div(4.0)))
                .isEqualTo(new ComputedProjection(
                        new ArithmeticOperand(BinaryOperator.DIV, path("salary"), Literal.of(4.0))));
        assertThat(projection(salary.mod(3.0)))
                .isEqualTo(new ComputedProjection(
                        new ArithmeticOperand(BinaryOperator.MOD, path("salary"), Literal.of(3.0))));
    }

    // ---------------------------------------------------------- PathValues.StringValue

    @Test
    @DisplayName("StringValue 的 contains/like：只有 contains 加 %，like 原样透传")
    void stringContainsAndLike() {
        StringPath<Order> name = customerName();

        assertThat(spec(name.contains("Ada"))).isEqualTo(likeSpec("%Ada%"));
        assertThat(spec(name.like("A%"))).isEqualTo(likeSpec("A%"));
    }

    @Test
    @DisplayName("StringValue 的 *IfNotEmpty / *IfNotNull：null 与空串都折成恒真占位")
    void stringShortCircuit() {
        StringPath<Order> name = customerName();

        assertThat(name.containsIfNotEmpty(null)).isNotInstanceOf(Op.class);
        assertThat(name.containsIfNotEmpty("")).isNotInstanceOf(Op.class);
        assertThat(spec(name.containsIfNotEmpty("Ada"))).isEqualTo(likeSpec("%Ada%"));

        assertThat(name.containsIfNotNull(null)).isNotInstanceOf(Op.class);
        assertThat(spec(name.containsIfNotNull("Ada"))).isEqualTo(likeSpec("%Ada%"));

        assertThat(name.likeIfNotNull(null)).isNotInstanceOf(Op.class);
        assertThat(spec(name.likeIfNotNull("A%"))).isEqualTo(likeSpec("A%"));

        assertThat(name.eqIfNotEmpty(null)).isNotInstanceOf(Op.class);
        assertThat(name.eqIfNotEmpty("")).isNotInstanceOf(Op.class);
        assertThat(spec(name.eqIfNotEmpty("Ada")))
                .isEqualTo(new Comparison(path("customer", "name"), ComparisonOperator.EQ, Literal.of("Ada")));
    }

    // ---------------------------------------------------------- PathValues.EntityValue.get

    @Test
    @DisplayName("EntityValue.get：路径链逐段追加，起点实体取自第一段")
    void entityValueGet() {
        EntityPath<Order, Customer> customer = EntityPath.of(Order::getCustomer);
        EntityPath<Order, Org> org = customer.get(Customer::getOrg);
        StringPath<Order> code = org.get(Org::getCode);

        assertThat(org.refs()).hasSize(2);
        assertThat(PathRefs.resolve(org.refs())).isEqualTo(path("customer", "org"));
        assertThat(code.refs()).hasSize(3);
        assertThat(PathRefs.resolve(code.refs())).isEqualTo(path("customer", "org", "code"));

        StringPath<Order> name = customerName();
        assertThat(name.refs()).hasSize(2);
        assertThat(PathRefs.resolve(name.refs())).isEqualTo(path("customer", "name"));
        assertThat(spec(name.eq("Ada")))
                .isEqualTo(new Comparison(path("customer", "name"), ComparisonOperator.EQ, Literal.of("Ada")));
    }

    // ------------------------------------ Conditions.PathOperatorValue（predicate.or(ref) 的返回）

    @ParameterizedTest(name = "{0}")
    @MethodSource("pathOperatorCases")
    @DisplayName("PathOperatorValue：条件包成 Leaf(OR, 空 refs, [parent, next])，前半条不能丢")
    void pathOperatorValue(String name, Operation expected, Spec expectedNext, OperatorCall call) {
        Predicate<Order> combined = call.apply(Path.of(Order::getAge).eq(3).or(Order::getScore));

        Op outer = (Op) combined;
        assertThat(outer.operation()).isEqualTo(Operation.OR);
        assertThat(outer.refs()).isEmpty();
        assertThat(outer.arguments()).hasSize(2);

        Op parent = (Op) outer.arguments().get(0);
        assertThat(parent.operation()).isEqualTo(Operation.EQ);
        assertThat(PathRefs.resolve(parent.refs())).isEqualTo(path("age"));
        assertThat(parent.arguments()).containsExactly(3);

        Op next = (Op) outer.arguments().get(1);
        assertThat(next.operation()).isEqualTo(expected);
        assertThat(PathRefs.resolve(next.refs())).isEqualTo(path("score"));

        assertThat(OpInterpreter.toSpec(outer))
                .isEqualTo(new Logical(
                        LogicalOperator.OR,
                        List.of(new Comparison(path("age"), ComparisonOperator.EQ, Literal.of(3)), expectedNext)));
    }

    @Test
    @DisplayName("PathOperatorValue.notBetween：先组合再取反，两层 refs 都为空")
    void pathOperatorValueNotBetween() {
        Predicate<Order> combined =
                Path.of(Order::getAge).eq(3).or(Order::getScore).notBetween(5, 6);

        Op negation = (Op) combined;
        assertThat(negation.operation()).isEqualTo(Operation.NOT);
        assertThat(negation.refs()).isEmpty();
        assertThat(negation.arguments()).hasSize(1);

        Op outer = (Op) negation.arguments().get(0);
        assertThat(outer.operation()).isEqualTo(Operation.OR);
        assertThat(outer.refs()).isEmpty();

        assertThat(OpInterpreter.toSpec(negation))
                .isEqualTo(Logical.not(Logical.or(
                        new Comparison(path("age"), ComparisonOperator.EQ, Literal.of(3)),
                        new Between(path("score"), Literal.of(5), Literal.of(6)))));
    }

    // ------------------------------------------------------- Conditions.AggregateValueImpl

    @Test
    @DisplayName("聚合值的比较：节点左侧是 AggregateOperand(subject)，不是 refs() 的路径")
    void aggregateValueCarriesSubject() {
        AggregateValue<Order, Double> max = Path.of(Order::getSalary).max();

        Op leaf = (Op) max.eq(100.0);
        assertThat(leaf.subject()).isSameAs(max);
        assertThat(((Op) Path.of(Order::getSalary).eq(100.0)).subject()).isNull();

        assertThat(OpInterpreter.toSpec(leaf)).isInstanceOfSatisfying(Comparison.class, comparison -> {
            assertThat(comparison.operator()).isEqualTo(ComparisonOperator.EQ);
            assertThat(comparison.left()).isEqualTo(new AggregateOperand(AggregateFunction.MAX, path("salary")));
            assertThat(comparison.right()).isEqualTo(Literal.of(100.0));
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("aggregateCases")
    @DisplayName("OpInterpreter.value 的六个聚合分支：subject 的 operation 决定 AggregateFunction")
    void aggregateOperandBranches(
            String name, Operation operation, AggregateFunction function, Supplier<Predicate<Order>> comparison) {
        Op leaf = (Op) comparison.get();

        Op subject = leaf.subject();
        assertThat(subject).isInstanceOf(Op.class);
        assertThat(subject.operation()).isEqualTo(operation);
        assertThat(PathRefs.resolve(subject.refs())).isEqualTo(path("salary"));

        assertThat(((Comparison) OpInterpreter.toSpec(leaf)).left())
                .isEqualTo(new AggregateOperand(function, path("salary")));
    }

    @Test
    @DisplayName("聚合值的全部比较算子：左侧固定是 AggregateOperand")
    void aggregateValueOperators() {
        AggregateValue<Order, Double> max = Path.of(Order::getSalary).max();
        Operand left = new AggregateOperand(AggregateFunction.MAX, path("salary"));

        assertThat(max.eqIfNotNull(null)).isNotInstanceOf(Op.class);
        assertThat(spec(max.eqIfNotNull(100.0)))
                .isEqualTo(new Comparison(left, ComparisonOperator.EQ, Literal.of(100.0)));
        assertThat(spec(max.ne(100.0))).isEqualTo(new Comparison(left, ComparisonOperator.NE, Literal.of(100.0)));
        assertThat(spec(max.ge(100.0))).isEqualTo(new Comparison(left, ComparisonOperator.GE, Literal.of(100.0)));
        assertThat(spec(max.gt(100.0))).isEqualTo(new Comparison(left, ComparisonOperator.GT, Literal.of(100.0)));
        assertThat(spec(max.le(100.0))).isEqualTo(new Comparison(left, ComparisonOperator.LE, Literal.of(100.0)));
        assertThat(spec(max.lt(100.0))).isEqualTo(new Comparison(left, ComparisonOperator.LT, Literal.of(100.0)));

        Between between = new Between(left, Literal.of(100.0), Literal.of(200.0));
        assertThat(spec(max.between(100.0, 200.0))).isEqualTo(between);
        assertThat(spec(max.between(Path.of(Order::getBonus), Path.of(Order::getBaseSalary))))
                .isEqualTo(new Between(left, path("bonus"), path("baseSalary")));
        assertThat(spec(max.notBetween(100.0, 200.0))).isEqualTo(Logical.not(between));
    }

    // ------------------------------------------------------ Conditions.ArithmeticValueImpl

    @Test
    @DisplayName("算术值：左操作数就是 refs()，链式算术把上一层塞进 subject")
    void arithmeticChain() {
        NumberValue<Order, Double> plus = Path.of(Order::getSalary).add(1000.0);

        assertThat(((Op) plus).subject()).isNull();
        assertThat(projection(plus))
                .isEqualTo(new ComputedProjection(
                        new ArithmeticOperand(BinaryOperator.ADD, path("salary"), Literal.of(1000.0))));

        NumberValue<Order, Double> chain = plus.sub(10.0).mul(2.0).div(4.0).mod(3.0);
        Operand expected = new ArithmeticOperand(
                BinaryOperator.MOD,
                new ArithmeticOperand(
                        BinaryOperator.DIV,
                        new ArithmeticOperand(
                                BinaryOperator.MUL,
                                new ArithmeticOperand(
                                        BinaryOperator.SUB,
                                        new ArithmeticOperand(BinaryOperator.ADD, path("salary"), Literal.of(1000.0)),
                                        Literal.of(10.0)),
                                Literal.of(2.0)),
                        Literal.of(4.0)),
                Literal.of(3.0));
        assertThat(projection(chain)).isEqualTo(new ComputedProjection(expected));
    }

    @Test
    @DisplayName("算术值的比较与区间：左侧是 ArithmeticOperand")
    void arithmeticOperators() {
        NumberValue<Order, Double> plus = Path.of(Order::getSalary).add(1000.0);
        Operand left = new ArithmeticOperand(BinaryOperator.ADD, path("salary"), Literal.of(1000.0));

        assertThat(plus.eqIfNotNull(null)).isNotInstanceOf(Op.class);
        assertThat(spec(plus.eq(5000.0))).isEqualTo(new Comparison(left, ComparisonOperator.EQ, Literal.of(5000.0)));
        assertThat(spec(plus.eqIfNotNull(5000.0)))
                .isEqualTo(new Comparison(left, ComparisonOperator.EQ, Literal.of(5000.0)));
        assertThat(spec(plus.ne(5000.0))).isEqualTo(new Comparison(left, ComparisonOperator.NE, Literal.of(5000.0)));
        assertThat(spec(plus.ge(5000.0))).isEqualTo(new Comparison(left, ComparisonOperator.GE, Literal.of(5000.0)));
        assertThat(spec(plus.gt(5000.0))).isEqualTo(new Comparison(left, ComparisonOperator.GT, Literal.of(5000.0)));
        assertThat(spec(plus.le(5000.0))).isEqualTo(new Comparison(left, ComparisonOperator.LE, Literal.of(5000.0)));
        assertThat(spec(plus.lt(5000.0))).isEqualTo(new Comparison(left, ComparisonOperator.LT, Literal.of(5000.0)));

        Between between = new Between(left, Literal.of(1000.0), Literal.of(2000.0));
        assertThat(spec(plus.between(1000.0, 2000.0))).isEqualTo(between);
        assertThat(spec(plus.between(Path.of(Order::getBonus), Path.of(Order::getBaseSalary))))
                .isEqualTo(new Between(left, path("bonus"), path("baseSalary")));
        assertThat(spec(plus.notBetween(1000.0, 2000.0))).isEqualTo(Logical.not(between));
    }

    // --------------------------------------------------------------- Conditions.AlwaysTrue

    @Test
    @DisplayName("恒真占位：逻辑组合时被折叠，一个节点都不产生")
    void alwaysTrueCombination() {
        Predicate<Order> alwaysTrue = Path.of(Order::getAge).eqIfNotNull(null);
        Predicate<Order> statusEquals = Path.of(Order::getStatus).eq("A");
        Spec statusSpec = new Comparison(path("status"), ComparisonOperator.EQ, Literal.of("A"));

        assertThat(alwaysTrue).isNotInstanceOf(Op.class);
        assertThat(spec(alwaysTrue.and(statusEquals))).isEqualTo(new Logical(LogicalOperator.AND, List.of(statusSpec)));
        assertThat(alwaysTrue.or(statusEquals)).isSameAs(alwaysTrue);

        assertThat(alwaysTrue.and(List.<Predicate<Order>>of())).isSameAs(alwaysTrue);
        assertThat(spec(alwaysTrue.and(List.of(statusEquals))))
                .isEqualTo(new Logical(LogicalOperator.AND, List.of(statusSpec)));
        assertThat(alwaysTrue.or(List.<Predicate<Order>>of())).isSameAs(alwaysTrue);
        assertThat(alwaysTrue.or(List.of(statusEquals))).isSameAs(alwaysTrue);

        assertThat(spec(alwaysTrue.not())).isEqualTo(Constant.FALSE);

        assertThat(alwaysTrue.or(Order::getAge).eq(3)).isSameAs(alwaysTrue);
    }

    // --------------------------------------------------------------- Conditions.Leaf 参数校验

    @Test
    @DisplayName("Leaf 的集合参数：null 与 null 元素拒绝，空集合返回自身")
    void leafCollectionArguments() {
        Predicate<Order> leaf = Path.of(Order::getAge).eq(3);

        assertThatThrownBy(() -> leaf.and((Collection<? extends Expression<Order, Boolean>>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("others");
        assertThatThrownBy(() -> leaf.or((Collection<? extends Expression<Order, Boolean>>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("others");
        assertThatThrownBy(() -> leaf.and(Arrays.<Expression<Order, Boolean>>asList((Expression<Order, Boolean>) null)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("others 元素不能为 null");

        assertThat(leaf.and(List.of())).isSameAs(leaf);
        assertThat(leaf.or(List.of())).isSameAs(leaf);
    }

    // ------------------------------------------------------------------ api.model.Tuple2~Tuple6

    @ParameterizedTest(name = "{0}")
    @MethodSource("tuples")
    @DisplayName("具名元组的按位置取值：下标越界报出中文列数")
    void tuplePositionalAccess(
            String name, Tuple tuple, List<Object> values, Class<?> type, String overflowMessagePrefix) {
        assertThat(tuple).isInstanceOf(type);
        assertThat(tuple.size()).isEqualTo(values.size());
        for (int index = 0; index < values.size(); index++) {
            assertThat(tuple.get(index)).isEqualTo(values.get(index));
        }

        assertThatThrownBy(() -> tuple.get(values.size()))
                .isInstanceOf(IndexOutOfBoundsException.class)
                .hasMessage(overflowMessagePrefix + values.size());
        assertThatThrownBy(() -> tuple.get(-1))
                .isInstanceOf(IndexOutOfBoundsException.class)
                .hasMessage(overflowMessagePrefix + "-1");
    }

    // --------------------------------------------------------------------------- fixture

    /// `predicate.or(ref)` 的返回值的调用形态：吃一个属性算子，吐一条组合后的条件。
    @FunctionalInterface
    private interface OperatorCall extends Function<PathOperator<Order, Integer, Predicate<Order>>, Predicate<Order>> {}

    private static Stream<Arguments> valueComparisons() {
        return Stream.of(
                comparisonRow("ne(值)", new Comparison(path("age"), ComparisonOperator.NE, Literal.of(3)), p -> p.ne(3)),
                comparisonRow(
                        "ne(表达式)",
                        new Comparison(path("age"), ComparisonOperator.NE, path("score")),
                        p -> p.ne(Path.of(Order::getScore))),
                comparisonRow("ge(值)", new Comparison(path("age"), ComparisonOperator.GE, Literal.of(3)), p -> p.ge(3)),
                comparisonRow(
                        "ge(表达式)",
                        new Comparison(path("age"), ComparisonOperator.GE, path("score")),
                        p -> p.ge(Path.of(Order::getScore))),
                comparisonRow("gt(值)", new Comparison(path("age"), ComparisonOperator.GT, Literal.of(3)), p -> p.gt(3)),
                comparisonRow(
                        "gt(表达式)",
                        new Comparison(path("age"), ComparisonOperator.GT, path("score")),
                        p -> p.gt(Path.of(Order::getScore))),
                comparisonRow("le(值)", new Comparison(path("age"), ComparisonOperator.LE, Literal.of(3)), p -> p.le(3)),
                comparisonRow(
                        "le(表达式)",
                        new Comparison(path("age"), ComparisonOperator.LE, path("score")),
                        p -> p.le(Path.of(Order::getScore))),
                comparisonRow("lt(值)", new Comparison(path("age"), ComparisonOperator.LT, Literal.of(3)), p -> p.lt(3)),
                comparisonRow(
                        "lt(表达式)",
                        new Comparison(path("age"), ComparisonOperator.LT, path("score")),
                        p -> p.lt(Path.of(Order::getScore))));
    }

    private static Stream<Arguments> pathOperatorCases() {
        return Stream.of(
                operatorRow("eq(值)", Operation.EQ, scoreComparison(ComparisonOperator.EQ, Literal.of(5)), p -> p.eq(5)),
                operatorRow(
                        "eq(表达式)",
                        Operation.EQ,
                        scoreComparison(ComparisonOperator.EQ, path("age")),
                        p -> p.eq(Path.of(Order::getAge))),
                operatorRow(
                        "eqIfNotNull(值)",
                        Operation.EQ,
                        scoreComparison(ComparisonOperator.EQ, Literal.of(5)),
                        p -> p.eqIfNotNull(5)),
                operatorRow("ne(值)", Operation.NE, scoreComparison(ComparisonOperator.NE, Literal.of(5)), p -> p.ne(5)),
                operatorRow(
                        "ne(表达式)",
                        Operation.NE,
                        scoreComparison(ComparisonOperator.NE, path("age")),
                        p -> p.ne(Path.of(Order::getAge))),
                operatorRow("ge(值)", Operation.GE, scoreComparison(ComparisonOperator.GE, Literal.of(5)), p -> p.ge(5)),
                operatorRow(
                        "ge(表达式)",
                        Operation.GE,
                        scoreComparison(ComparisonOperator.GE, path("age")),
                        p -> p.ge(Path.of(Order::getAge))),
                operatorRow("gt(值)", Operation.GT, scoreComparison(ComparisonOperator.GT, Literal.of(5)), p -> p.gt(5)),
                operatorRow(
                        "gt(表达式)",
                        Operation.GT,
                        scoreComparison(ComparisonOperator.GT, path("age")),
                        p -> p.gt(Path.of(Order::getAge))),
                operatorRow("le(值)", Operation.LE, scoreComparison(ComparisonOperator.LE, Literal.of(5)), p -> p.le(5)),
                operatorRow(
                        "le(表达式)",
                        Operation.LE,
                        scoreComparison(ComparisonOperator.LE, path("age")),
                        p -> p.le(Path.of(Order::getAge))),
                operatorRow("lt(值)", Operation.LT, scoreComparison(ComparisonOperator.LT, Literal.of(5)), p -> p.lt(5)),
                operatorRow(
                        "lt(表达式)",
                        Operation.LT,
                        scoreComparison(ComparisonOperator.LT, path("age")),
                        p -> p.lt(Path.of(Order::getAge))),
                operatorRow(
                        "geIfNotNull(值)",
                        Operation.GE,
                        scoreComparison(ComparisonOperator.GE, Literal.of(5)),
                        p -> p.geIfNotNull(5)),
                operatorRow(
                        "gtIfNotNull(值)",
                        Operation.GT,
                        scoreComparison(ComparisonOperator.GT, Literal.of(5)),
                        p -> p.gtIfNotNull(5)),
                operatorRow(
                        "leIfNotNull(值)",
                        Operation.LE,
                        scoreComparison(ComparisonOperator.LE, Literal.of(5)),
                        p -> p.leIfNotNull(5)),
                operatorRow(
                        "ltIfNotNull(值)",
                        Operation.LT,
                        scoreComparison(ComparisonOperator.LT, Literal.of(5)),
                        p -> p.ltIfNotNull(5)),
                operatorRow("isNull", Operation.IS_NULL, new NullCheck(path("score"), false), p -> p.isNull()),
                operatorRow("isNotNull", Operation.IS_NOT_NULL, new NullCheck(path("score"), true), p -> p.isNotNull()),
                operatorRow(
                        "in(集合)", Operation.IN, new In(path("score"), List.of(5, 6), false), p -> p.in(List.of(5, 6))),
                operatorRow("in(变长)", Operation.IN, new In(path("score"), List.of(5, 6), false), p -> p.in(5, 6)),
                operatorRow(
                        "notIn(集合)",
                        Operation.NOT_IN,
                        new In(path("score"), List.of(5, 6), true),
                        p -> p.notIn(List.of(5, 6))),
                operatorRow(
                        "notIn(变长)", Operation.NOT_IN, new In(path("score"), List.of(5, 6), true), p -> p.notIn(5, 6)),
                operatorRow(
                        "inIfNotNull(集合)",
                        Operation.IN,
                        new In(path("score"), List.of(5), false),
                        p -> p.inIfNotNull(List.of(5))),
                operatorRow(
                        "between(值)",
                        Operation.BETWEEN,
                        new Between(path("score"), Literal.of(5), Literal.of(6)),
                        p -> p.between(5, 6)),
                operatorRow(
                        "between(表达式)",
                        Operation.BETWEEN,
                        new Between(path("score"), path("age"), path("level")),
                        p -> p.between(Path.of(Order::getAge), Path.of(Order::getLevel))));
    }

    private static Stream<Arguments> aggregateCases() {
        return Stream.of(
                aggregateRow("max", Operation.MAX, AggregateFunction.MAX, () -> Path.of(Order::getSalary)
                        .max()
                        .eq(1.0)),
                aggregateRow("min", Operation.MIN, AggregateFunction.MIN, () -> Path.of(Order::getSalary)
                        .min()
                        .eq(1.0)),
                aggregateRow("avg", Operation.AVG, AggregateFunction.AVG, () -> Path.of(Order::getSalary)
                        .avg()
                        .eq(1.0)),
                aggregateRow("sum", Operation.SUM, AggregateFunction.SUM, () -> Path.of(Order::getSalary)
                        .sum()
                        .eq(1.0)),
                aggregateRow("count", Operation.COUNT, AggregateFunction.COUNT, () -> Path.of(Order::getSalary)
                        .count()
                        .eq(5L)),
                aggregateRow("countDistinct", Operation.COUNT_DISTINCT, AggregateFunction.COUNT_DISTINCT, () -> Path.of(
                                Order::getSalary)
                        .countDistinct()
                        .eq(5L)));
    }

    private static Stream<Arguments> tuples() {
        return Stream.of(
                tupleRow("Tuple2", new Tuple2<>("a", 1), List.of("a", 1), Tuple2.class, "两列元组没有下标 "),
                tupleRow("Tuple3", new Tuple3<>("a", 1, 2L), List.of("a", 1, 2L), Tuple3.class, "三列元组没有下标 "),
                tupleRow("Tuple4", new Tuple4<>("a", 1, 2L, 3.0), List.of("a", 1, 2L, 3.0), Tuple4.class, "四列元组没有下标 "),
                tupleRow(
                        "Tuple5",
                        new Tuple5<>("a", 1, 2L, 3.0, true),
                        List.of("a", 1, 2L, 3.0, true),
                        Tuple5.class,
                        "五列元组没有下标 "),
                tupleRow(
                        "Tuple6",
                        new Tuple6<>("a", 1, 2L, 3.0, true, 'x'),
                        List.of("a", 1, 2L, 3.0, true, 'x'),
                        Tuple6.class,
                        "六列元组没有下标 "));
    }

    private static Arguments comparisonRow(
            String name, Spec expected, Function<Path<Order, Integer>, Predicate<Order>> call) {
        return Arguments.of(name, expected, call);
    }

    private static Arguments operatorRow(String name, Operation expected, Spec expectedNext, OperatorCall call) {
        return Arguments.of(name, expected, expectedNext, call);
    }

    private static Arguments aggregateRow(
            String name, Operation operation, AggregateFunction function, Supplier<Predicate<Order>> comparison) {
        return Arguments.of(name, operation, function, comparison);
    }

    private static Arguments tupleRow(
            String name, Tuple tuple, List<Object> values, Class<?> type, String overflowMessagePrefix) {
        return Arguments.of(name, tuple, values, type, overflowMessagePrefix);
    }

    /// 解释条件值：`api` 的 `Predicate` 实现同时也是 `Op`，只有短路产生的恒真占位不是。
    private static Spec spec(Expression<Order, Boolean> predicate) {
        return OpInterpreter.toSpec((Op) predicate);
    }

    private static Projection projection(Expression<Order, ?> expression) {
        return OpInterpreter.toProjection((Op) expression);
    }

    private static Spec scoreComparison(ComparisonOperator operator, Operand right) {
        return new Comparison(path("score"), operator, right);
    }

    private static Spec likeSpec(String pattern) {
        return new Comparison(path("customer", "name"), ComparisonOperator.LIKE, Literal.of(pattern));
    }

    private static StringPath<Order> customerName() {
        return EntityPath.of(Order::getCustomer).get(Customer::getName);
    }

    private static AttributePath path(String... names) {
        return AttributePath.of(Order.class, names);
    }

    @SuppressWarnings("unused")
    private static final class Order {

        private Integer age;
        private Integer score;
        private Integer level;
        private Double salary;
        private Double bonus;
        private Double baseSalary;
        private String status;
        private Customer customer;

        public Integer getAge() {
            return age;
        }

        public Integer getScore() {
            return score;
        }

        public Integer getLevel() {
            return level;
        }

        public Double getSalary() {
            return salary;
        }

        public Double getBonus() {
            return bonus;
        }

        public Double getBaseSalary() {
            return baseSalary;
        }

        public String getStatus() {
            return status;
        }

        public Customer getCustomer() {
            return customer;
        }
    }

    @SuppressWarnings("unused")
    private static final class Customer {

        private String name;
        private Org org;

        public String getName() {
            return name;
        }

        public Org getOrg() {
            return org;
        }
    }

    @SuppressWarnings("unused")
    private static final class Org {

        private String code;

        public String getCode() {
            return code;
        }
    }
}
