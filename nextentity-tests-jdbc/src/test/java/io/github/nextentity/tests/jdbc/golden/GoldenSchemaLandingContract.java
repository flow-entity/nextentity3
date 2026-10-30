package io.github.nextentity.tests.jdbc.golden;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.api.Expression;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.ProjectionMapper;
import io.github.nextentity.api.model.Tuple;
import io.github.nextentity.testing.golden.GoldenFixtures;
import io.github.nextentity.testing.golden.GoldenSchema;
import io.github.nextentity.testing.golden.GoldenSchema.OrderState;
import io.github.nextentity.testing.golden.GoldenSchema.Tier;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// golden schema 的 JDBC 落地证据（后端无关契约）：H2 与三条容器通道跑**同一批**用例，
/// 差异只在 [GoldenJdbcSupport] 的方言脚本与数据源。
///
/// 覆盖：`DECIMAL(12,2)` 的 scale、`BOOLEAN`、`Instant`/`LocalDateTime`、`@Enumerated(STRING)` 与
/// `@Convert`（库里小写 code）两条枚举路径的**原始列值**、`@Embedded` 扁平列、`@ManyToOne` 的外键列、
/// `@Version` 自增、`@Column(updatable=false)` 条件更新被拒（UOE）、`fetch` 的 fail-fast UOE、
/// 条件 update/delete 的受影响行数与整表删除。
///
/// oracle 分层：常量对 [GoldenFixtures] 只钉映射语义；与 JPA 侧同名用例（
/// `io.github.nextentity.tests.jpa.golden.GoldenSchemaLandingTest`）的差值证明两条实现路径一致。
public abstract class GoldenSchemaLandingContract {

    /// 各通道的接线。
    ///
    /// @return 已建好 golden 三表的数据源接线
    protected abstract GoldenJdbcSupport support();

    @BeforeEach
    void seedGoldenSchema() {
        support().clear();
        support().seedGolden();
    }

    @AfterEach
    void reset() {
        support().clear();
    }

    @Test
    @DisplayName("客户表：DECIMAL(12,2)/BOOLEAN/Instant/@Enumerated(STRING)/@Embedded 全部按声明类型读回")
    void customerColumnsRoundTripWithDeclaredTypes() {
        GoldenSchema.Customer alice = customer(1L);

        assertThat(alice.name()).isEqualTo("alice");
        assertThat(alice.tier()).isEqualTo(Tier.BASIC);
        assertThat(alice.creditLimit()).isEqualByComparingTo(GoldenFixtures.ALICE_CREDIT);
        assertThat(alice.creditLimit().scale())
                .as("DECIMAL(12,2) 的 scale 必须原样读回")
                .isEqualTo(2);
        assertThat(alice.active()).isTrue();
        assertThat(alice.joinedAt()).isEqualTo(GoldenFixtures.ALICE_JOINED_AT);
        assertThat(alice.address()).isNotNull();
        assertThat(alice.address().street()).isEqualTo("1 Main St");
        assertThat(alice.address().city()).isEqualTo("Springfield");
        assertThat(alice.address().zip()).isEqualTo("11111");
    }

    @Test
    @DisplayName("可空列与整组为空的 @Embedded 读回 null，不被默认值填充")
    void emptyEmbeddedGroupAndNullableColumnsStayNull() {
        GoldenSchema.Customer bob = customer(2L);

        assertThat(bob.address()).as("整组列全 NULL 时 @Embedded 属性必须是 null").isNull();
        assertThat(bob.active()).isFalse();
        assertThat(bob.tier()).isEqualTo(Tier.PRO);

        GoldenSchema.Order order = order(102L);
        assertThat(order.note()).isNull();
        assertThat(order.placedAt()).isEqualTo(GoldenFixtures.ORDER_102_PLACED_AT);
    }

    @Test
    @DisplayName("@Convert 枚举：读回枚举对象，库里存的是小写 code")
    void convertedStateRoundTripsAsLowercaseCode() {
        GoldenSchema.Order order = order(101L);

        assertThat(order.state()).isEqualTo(OrderState.NEW);
        assertThat(support().queryString("select state from golden_order where id = 101"))
                .as("@Convert 写的是小写 code，不是枚举名")
                .isEqualTo("new");
        assertThat(order(103L).state()).isEqualTo(OrderState.SHIPPED);
        assertThat(support().queryString("select state from golden_order where id = 103"))
                .isEqualTo("shipped");
    }

    @Test
    @DisplayName("@Enumerated(STRING)：库里存的是枚举名")
    void enumeratedTierUsesEnumNameOnDisk() {
        assertThat(support().queryString("select tier from golden_customer where id = 1"))
                .isEqualTo("BASIC");
        assertThat(support().queryString("select tier from golden_customer where id = 3"))
                .isEqualTo("ENTERPRISE");
    }

    @Test
    @DisplayName("@ManyToOne：外键列由关联对象的主键写入")
    void manyToOneWritesForeignKeyColumn() {
        assertThat(support().queryString("select customer_id from golden_order where id = 101"))
                .isEqualTo("1");
        assertThat(support().queryString("select customer_id from golden_order where id = 103"))
                .isEqualTo("2");
        assertThat(order(103L).total()).isEqualByComparingTo(GoldenFixtures.ORDER_103_TOTAL);
    }

    @Test
    @DisplayName("@OneToMany 的另一侧：订单行按订单分组、可空发货时间真写 NULL")
    void orderLinesRoundTripWithNullableShippedAt() {
        // JDBC 侧读回只装外键/标量列、不填充关联对象（USAGE §13.4 的披露），所以分组证据走 SQL 计数，
        // 而不是 `line.order().id()`——后者在 JDBC 上本来就是 null，用它当 oracle 会把限制写成失败。
        assertThat(support().queryString("select count(*) from golden_order_line where order_id = 101"))
                .isEqualTo("2");
        assertThat(support().queryString("select count(*) from golden_order_line where order_id = 102"))
                .isEqualTo("1");
        assertThat(support().queryString("select count(*) from golden_order_line where order_id = 103"))
                .isEqualTo("2");
        assertThat(support().queryString("select count(*) from golden_order_line where shipped_at is null"))
                .as("两行未发货：可空时间列必须真写 NULL")
                .isEqualTo("2");
    }

    /// **契约**（3.1.0 定下，见 USAGE §10.1）：批量条件更新只写调用方声明的列，**不自增也不校验
    /// `@Version`**；乐观锁只在单实体 `update(entity)` 路径生效。需要并发保护时走单实体 update，
    /// 或自己在 `where(...)` 里显式带上版本列。JPA 侧同名用例钉住同一契约。
    @Test
    @DisplayName("契约：批量条件更新不触碰 @Version（乐观锁只在单实体 update 路径生效）")
    void bulkConditionalUpdateDoesNotTouchVersion() {
        assertThat(support().queryString("select version from golden_customer where id = 1"))
                .isEqualTo("0");

        int affected = support()
                .operations(GoldenSchema.Customer.class)
                .update()
                .set(Path.of(GoldenSchema.Customer::creditLimit), new BigDecimal("150.00"))
                .where(GoldenSchema.Customer::id)
                .eq(1L)
                .execute();

        assertThat(affected).isEqualTo(1);
        assertThat(customer(1L).creditLimit()).isEqualByComparingTo("150.00");
        assertThat(support().queryString("select version from golden_customer where id = 1"))
                .as("契约：批量路径不参与乐观锁，版本号原样保留（JPA 侧同契约）")
                .isEqualTo("0");
        assertThat(customer(2L).creditLimit()).isEqualByComparingTo(GoldenFixtures.BOB_CREDIT);
    }

    @Test
    @DisplayName("单实体 update：@Version 自增并回写到调用方对象")
    void entityUpdateIncrementsVersionOnCallerObject() {
        GoldenSchema.Customer alice = customer(1L);
        assertThat(alice.version()).isEqualTo(0L);

        support().operations(GoldenSchema.Customer.class).update(alice);

        assertThat(alice.version()).as("新版本号必须回写到调用方对象，否则下一次更新撞乐观锁").isEqualTo(1L);
        assertThat(support().queryString("select version from golden_customer where id = 1"))
                .isEqualTo("1");
    }

    @Test
    @DisplayName("条件 delete：只删匹配行并返回行数")
    void conditionalDeleteRemovesOnlyMatchingRow() {
        int affected = support()
                .operations(GoldenSchema.OrderLine.class)
                .delete()
                .where(GoldenSchema.OrderLine::id)
                .eq(1004L)
                .execute();

        assertThat(affected).isEqualTo(1);
        assertThat(support().query(GoldenSchema.OrderLine.class)).hasSize(4);
        assertThat(support().queryString("select count(*) from golden_order_line where id = 1004"))
                .isEqualTo("0");
    }

    @Test
    @DisplayName("不带 where 的 delete：整表删除并返回全部行数")
    void deleteWithoutWhereEmptiesWholeTable() {
        int affected =
                support().operations(GoldenSchema.OrderLine.class).delete().execute();

        assertThat(affected).isEqualTo(5);
        assertThat(support().query(GoldenSchema.OrderLine.class)).isEmpty();
    }

    @Test
    @DisplayName("@Column(updatable=false) 的列出现在条件 update 的 SET 里：fail-fast UOE，文案与 JPA 逐字一致")
    void conditionalUpdateOfNonUpdatableColumnIsRejected() {
        assertThatThrownBy(() -> support()
                        .operations(GoldenSchema.Order.class)
                        .update()
                        .set(Path.of(GoldenSchema.Order::note), "changed")
                        .where(GoldenSchema.Order::id)
                        .eq(101L)
                        .execute())
                .isInstanceOf(UnsupportedOperationException.class)
                .as("两后端的拒绝文案必须逐字一致（同一个 GoldenSchema.Order 映射出同一个属性全名）")
                .hasMessage("条件更新不支持 @Column(updatable=false) 列: " + GoldenSchema.Order.class.getName() + ".note");
        assertThat(support().queryString("select note from golden_order where id = 101"))
                .as("被拒绝的语句不得落库")
                .isEqualTo("first");
    }

    @Test
    @DisplayName("fetch(预加载关联)：JDBC 后端 fail-fast UOE，不静默降级成逐行查询")
    void fetchIsRejectedFailFast() {
        assertThatThrownBy(() -> support()
                        .operations(GoldenSchema.Order.class)
                        .query()
                        .fetch(GoldenSchema.Order::customer)
                        .list())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("JDBC 后端不支持 fetch")
                .hasMessageContaining("project(ProjectionMapper)");
    }

    @Test
    @DisplayName("@Embedded 路径参与条件：直接命中扁平列")
    void embeddedPathFiltersFlattenedColumn() {
        List<GoldenSchema.Customer> springfield = support()
                .operations(GoldenSchema.Customer.class)
                .query()
                .where(EntityPath.of(GoldenSchema.Customer::address).get(GoldenSchema.Address::city))
                .eq("Springfield")
                .list();

        assertThat(springfield).extracting(GoldenSchema.Customer::id).containsExactly(1L);
    }

    /// 嵌套关联投影：`project(ProjectionMapper)` 里声明「订单 id + 客户名」，由后端渲染 JOIN 并装回。
    ///
    /// JDBC 后端不支持 `fetch`（上一条用例钉住的 fail-fast），嵌套关联列只能走映射器——
    /// 这条用例证明「不走 fetch 也能取关联列」，是那条拒绝文案指出的替代路径的可执行证据。
    @Test
    @DisplayName("嵌套关联投影：project 映射器取关联列（fetch 的替代路径）")
    void nestedAssociationProjectionMapsJoinedColumn() {
        List<OrderCustomer> rows = support()
                .operations(GoldenSchema.Order.class)
                .query()
                .project(new OrderCustomerMapper())
                .orderBy(GoldenSchema.Order::id)
                .asc()
                .list();

        assertThat(rows)
                .containsExactly(
                        new OrderCustomer(101L, "alice"),
                        new OrderCustomer(102L, "alice"),
                        new OrderCustomer(103L, "bob"));
    }

    private GoldenSchema.Customer customer(long id) {
        GoldenSchema.Customer customer = support()
                .operations(GoldenSchema.Customer.class)
                .query()
                .where(GoldenSchema.Customer::id)
                .eq(id)
                .first();
        assertThat(customer).as("customer id=%s 必须存在", id).isNotNull();
        return customer;
    }

    private GoldenSchema.Order order(long id) {
        GoldenSchema.Order order = support()
                .operations(GoldenSchema.Order.class)
                .query()
                .where(GoldenSchema.Order::id)
                .eq(id)
                .first();
        assertThat(order).as("order id=%s 必须存在", id).isNotNull();
        return order;
    }

    /// 嵌套关联投影的结果类型：订单 id + 客户显示名。
    ///
    /// @param orderId      订单主键
    /// @param customerName 客户显示名
    private record OrderCustomer(long orderId, String customerName) {}

    /// 两列映射器：下标常量紧邻表达式列表，列序错位在审查里能直接看见。
    private static final class OrderCustomerMapper implements ProjectionMapper<GoldenSchema.Order, OrderCustomer> {

        private static final int IDX_ORDER_ID = 0;

        private static final int IDX_CUSTOMER_NAME = 1;

        @Override
        public List<Expression<GoldenSchema.Order, ?>> expressions() {
            return List.of(
                    Path.of(GoldenSchema.Order::id),
                    EntityPath.of(GoldenSchema.Order::customer).get(GoldenSchema.Customer::name));
        }

        @Override
        public OrderCustomer map(Tuple row) {
            return new OrderCustomer((Long) row.get(IDX_ORDER_ID), (String) row.get(IDX_CUSTOMER_NAME));
        }
    }
}
