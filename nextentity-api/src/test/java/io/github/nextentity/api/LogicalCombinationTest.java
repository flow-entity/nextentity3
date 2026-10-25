package io.github.nextentity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.WireFormatFixture.Order;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 逻辑组合的线格式：`Leaf` 的 `and`/`or`/`not`、`AlwaysTrue` 占位的折叠规则、集合形态的边界。
///
/// 本套件钉的是 **`api` 自己的线格式契约**（`operation` / `arguments` / `refs`）。
/// 「`AND`/`OR`/`NOT` 如何渲染成 `core.spec.Logical`」的证据在 `nextentity-core` 的
/// `ApiOperatorAssemblyTest`，两层不互相代替。
///
/// 分组必须原样保留：`a.and(b).or(c)` 的 `arguments()` 是 `[Leaf(AND, [a, b]), c]`，
/// 拍平成 `[a, b, c]` 会改变布尔结果，所以这里钉的是「接收者在最前、其余按入参顺序」。
@DisplayName("逻辑组合：Leaf 与 AlwaysTrue 的线格式")
class LogicalCombinationTest {

    @Test
    @DisplayName("单条组合：AND/OR 的 arguments 是 [接收者, 对方]，NOT 是 [接收者]")
    void singleCombination() {
        Predicate<Order> age = ageEquals(3);
        Predicate<Order> score = scoreGreaterThan(5);

        Op and = (Op) age.and(score);
        assertThat(and.operation()).isEqualTo(Operation.AND);
        assertThat(and.refs()).isEmpty();
        assertThat(and.subject()).isNull();
        assertThat(and.arguments()).containsExactly(age, score);

        Op or = (Op) age.or(score);
        assertThat(or.operation()).isEqualTo(Operation.OR);
        assertThat(or.refs()).isEmpty();
        assertThat(or.arguments()).containsExactly(age, score);

        Op not = (Op) age.not();
        assertThat(not.operation()).isEqualTo(Operation.NOT);
        assertThat(not.refs()).isEmpty();
        assertThat(not.arguments()).containsExactly(age);
    }

    @Test
    @DisplayName("集合形态：接收者排在其它条件之前，空集合返回同一个对象")
    void collectionCombination() {
        Predicate<Order> age = ageEquals(3);
        Predicate<Order> score = scoreGreaterThan(5);
        Predicate<Order> status = statusEquals("PAID");

        Op and = (Op) age.and(List.of(score, status));
        assertThat(and.operation()).isEqualTo(Operation.AND);
        assertThat(and.refs()).isEmpty();
        assertThat(and.arguments()).containsExactly(age, score, status);

        Op or = (Op) age.or(List.of(score, status));
        assertThat(or.operation()).isEqualTo(Operation.OR);
        assertThat(or.refs()).isEmpty();
        assertThat(or.arguments()).containsExactly(age, score, status);

        assertThat(age.and(List.of())).isSameAs(age);
        assertThat(age.or(List.of())).isSameAs(age);
    }

    @Test
    @DisplayName("集合形态的入参校验：null 集合与 null 元素都拒绝，消息点名 others")
    void collectionArgumentsAreChecked() {
        Predicate<Order> age = ageEquals(3);

        assertThatThrownBy(() -> age.and((Collection<? extends Expression<Order, Boolean>>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("others");
        assertThatThrownBy(() -> age.or((Collection<? extends Expression<Order, Boolean>>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("others");
        assertThatThrownBy(() -> age.and(Arrays.<Expression<Order, Boolean>>asList((Expression<Order, Boolean>) null)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("others 元素不能为 null");
        assertThatThrownBy(() -> age.or(Arrays.<Expression<Order, Boolean>>asList((Expression<Order, Boolean>) null)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("others 元素不能为 null");
    }

    @Test
    @DisplayName("恒真占位：TRUE ∨ x = TRUE 返回自身，TRUE ∧ x 只保留对方，取反是 NOT(TRUE)")
    void alwaysTrueCombination() {
        Predicate<Order> alwaysTrue = Path.of(Order::getAge).eqIfNotNull(null);
        Predicate<Order> score = scoreGreaterThan(5);

        Op and = (Op) alwaysTrue.and(score);
        assertThat(and.operation()).isEqualTo(Operation.AND);
        assertThat(and.refs()).isEmpty();
        assertThat(and.arguments()).containsExactly(alwaysTrue, score);

        assertThat(alwaysTrue.or(score)).isSameAs(alwaysTrue);

        Op andAll = (Op) alwaysTrue.and(List.of(score));
        assertThat(andAll.operation()).isEqualTo(Operation.AND);
        assertThat(andAll.refs()).isEmpty();
        assertThat(andAll.arguments()).containsExactly(score);

        assertThat(alwaysTrue.and(List.of())).isSameAs(alwaysTrue);
        assertThat(alwaysTrue.or(List.of())).isSameAs(alwaysTrue);
        assertThat(alwaysTrue.or(List.of(score))).isSameAs(alwaysTrue);

        Op not = (Op) alwaysTrue.not();
        assertThat(not.operation()).isEqualTo(Operation.NOT);
        assertThat(not.arguments()).containsExactly(alwaysTrue);
    }

    @Test
    @DisplayName("短路链上的空值：TRUE.or(ref).eqIfNotNull(null) 仍返回恒真占位，而不是 null")
    void skippedConditionOnSuppressedChainStaysAlwaysTrue() {
        // 两个构造点决定了 skip() 的三种情形：
        //   - `Leaf.or(PathRef)`    → parent 非 null、suppressed=false（只丢本次条件）
        //   - `AlwaysTrue.or(PathRef)` → parent 为 null、suppressed=true（整条链恒真）
        // 这里走后者。`skip()` 若把 `alwaysTrue()` 那一步删掉，返回的就是 null，
        // 下游 `predicate.and(...)` 直接 NPE——「可选条件」会变成「空指针」。
        Predicate<Order> alwaysTrue = Path.of(Order::getAge).eqIfNotNull(null);
        PathOperator<Order, Integer, Predicate<Order>> chain = alwaysTrue.or(Order::getAge);

        assertThat(chain.eqIfNotNull(null)).isSameAs(alwaysTrue);
    }

    @Test
    @DisplayName("恒真占位的集合入参同样校验 null 集合与 null 元素")
    void alwaysTrueCollectionArgumentsAreChecked() {
        Predicate<Order> alwaysTrue = Path.of(Order::getAge).eqIfNotNull(null);

        assertThatThrownBy(() -> alwaysTrue.and((Collection<? extends Expression<Order, Boolean>>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("others");
        assertThatThrownBy(() -> alwaysTrue.or((Collection<? extends Expression<Order, Boolean>>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("others");
        assertThatThrownBy(() ->
                        alwaysTrue.and(Arrays.<Expression<Order, Boolean>>asList((Expression<Order, Boolean>) null)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("others 元素不能为 null");
    }

    private static Predicate<Order> ageEquals(int value) {
        return Path.of(Order::getAge).eq(value);
    }

    private static Predicate<Order> scoreGreaterThan(int value) {
        return Path.of(Order::getScore).gt(value);
    }

    private static Predicate<Order> statusEquals(String value) {
        return Path.of(Order::getStatus).eq(value);
    }
}
