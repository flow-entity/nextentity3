package io.github.nextentity.api;

import static io.github.nextentity.api.WireFormatFixture.AGE;
import static io.github.nextentity.api.WireFormatFixture.CUSTOMER_NAME;
import static io.github.nextentity.api.WireFormatFixture.ORG_CODE;
import static io.github.nextentity.api.WireFormatFixture.apply;
import static io.github.nextentity.api.WireFormatFixture.order;
import static io.github.nextentity.api.WireFormatFixture.read;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.WireFormatFixture.Customer;
import io.github.nextentity.api.WireFormatFixture.Order;
import io.github.nextentity.api.WireFormatFixture.Org;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `EntityPath.of(...).get(...)` 的线格式：每次 `get` 把一段方法引用追加到链尾。
///
/// 本套件钉的是 **`api` 自己的线格式契约**（`refs()` 的段数与逐级 `apply` 的结果）。
/// 把这条链解析成 `AttributePath` 是后端的职责，证据在 `nextentity-core` 的 `ApiOperatorAssemblyTest`，
/// 两层不互相代替。
///
/// 断言逐级 `apply` 而不是只断言「链长等于 3」：链长只能证明段数对，证明不了「第 2 段真的是
/// `Customer::getOrg`」——两段写反、或整条链被复制成同一个 lambda，链长都还是 3。
@DisplayName("EntityPath：嵌套链的逐级 apply")
class EntityPathNavigationTest {

    @Test
    @DisplayName("get(PathRef)：链逐段追加，每一段 apply 到上一段的结果上")
    void getPathRefAppendsSegment() {
        EntityPath<Order, Customer> customer = EntityPath.of(Order::getCustomer);
        EntityPath<Order, Org> org = customer.get(Customer::getOrg);
        Order order = order();

        assertThat(customer.refs()).hasSize(1);
        assertThat(apply(customer.refs().get(0), order)).isSameAs(order.getCustomer());

        assertThat(org.refs()).hasSize(2);
        Object readCustomer = apply(org.refs().get(0), order);
        assertThat(readCustomer).isSameAs(order.getCustomer());
        assertThat(apply(org.refs().get(1), readCustomer))
                .isSameAs(order.getCustomer().getOrg());
        assertThat(read(org.refs(), order)).isSameAs(order.getCustomer().getOrg());
    }

    @Test
    @DisplayName("get(StringRef)：末段是字符串属性，条件挂在整条链上")
    void getStringRefEndsChainWithStringPath() {
        EntityPath<Order, Customer> customer = EntityPath.of(Order::getCustomer);
        EntityPath<Order, Org> org = customer.get(Customer::getOrg);
        StringPath<Order> code = org.get(Org::getCode);
        Order order = order();

        assertThat(code.refs()).hasSize(3);
        Object readCustomer = apply(code.refs().get(0), order);
        Object readOrg = apply(code.refs().get(1), readCustomer);
        assertThat(readOrg).isSameAs(order.getCustomer().getOrg());
        assertThat(apply(code.refs().get(2), readOrg)).isEqualTo(ORG_CODE);
        assertThat(read(code.refs())).isEqualTo(ORG_CODE);

        Op condition = (Op) code.eq(ORG_CODE);
        assertThat(condition.operation()).isEqualTo(Operation.EQ);
        assertThat(condition.arguments()).containsExactly(ORG_CODE);
        assertThat(condition.subject()).isNull();
        assertThat(read(condition.refs())).isEqualTo(ORG_CODE);
    }

    @Test
    @DisplayName("两段链：customer.get(Customer::getName) 的 refs 只有两段，逐级 apply 才读得到 name")
    void twoSegmentChainReadsLeafField() {
        StringPath<Order> name = EntityPath.of(Order::getCustomer).get(Customer::getName);
        Order order = order();

        assertThat(name.refs()).hasSize(2);
        Object readCustomer = apply(name.refs().get(0), order);
        assertThat(readCustomer).isSameAs(order.getCustomer());
        assertThat(apply(name.refs().get(1), readCustomer)).isEqualTo(CUSTOMER_NAME);
        assertThat(read(name.refs())).isEqualTo(CUSTOMER_NAME);
    }

    @Test
    @DisplayName("EntityPath.of 与 Path.of 都是单段链，refs 都指向同一段方法引用")
    void singleSegmentChains() {
        Order order = order();

        Path<Order, Integer> age = Path.of(Order::getAge);
        assertThat(age.refs()).hasSize(1);
        assertThat(apply(age.refs().get(0), order)).isEqualTo(AGE);
        assertThat(read(age.refs())).isEqualTo(AGE);

        EntityPath<Order, Customer> customer = EntityPath.of(Order::getCustomer);
        assertThat(customer.refs()).hasSize(1);
        assertThat(apply(customer.refs().get(0), order)).isSameAs(order.getCustomer());
    }
}
