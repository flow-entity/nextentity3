package io.github.nextentity.tests.jpa.golden;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.testing.golden.GoldenFixtures;
import io.github.nextentity.testing.golden.GoldenSchema;
import io.github.nextentity.testing.golden.GoldenSchema.OrderState;
import io.github.nextentity.testing.golden.GoldenSchema.Tier;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// golden schema 的 JPA 落地证据（后端无关契约）：H2 与 MySQL/PostgreSQL 容器通道跑**同一批**用例，
/// 差异只在 [JpaGoldenSupport] 连哪个库。DDL 不手写：三张表由同一份实体注解经 `hbm2ddl` 建出，
/// 与 JDBC 侧 `schema-*.sql` 的 golden 段互相校验。
///
/// 覆盖的映射形态：`DECIMAL(12,2)` 的 scale、`BOOLEAN`、`Instant`（带时区）、`LocalDateTime`、
/// `@Enumerated(STRING)` 与 `@Convert`（小写 code）两条枚举路径、`@Embedded`（含整组为空）、
/// `@Column(updatable=false)` 只读列、`@Version`、`@ManyToOne` 的 FK 列、`@OneToMany` 集合关联。
///
/// oracle 分层：常量只钉「写什么、读回什么」的映射语义；正确性证据是同一份夹具在 JDBC 侧
/// 四个方言上读出同样的值（跨方言对拍），以及本类里「不 fetch vs fetch」的 SQL 条数对照（差分）。
public abstract class JpaGoldenSchemaLandingContract {

    /// 各通道的接线。
    ///
    /// @return 已能建表/清表/写夹具的 JPA 装置
    protected abstract JpaGoldenSupport support();

    @BeforeEach
    void seedGoldenSchema() {
        support().clear();
        support().seedGolden();
    }

    @AfterEach
    void reset() {
        support().clear();
    }

    /// 客户表：主键外的每一列都按声明类型读回，金额 scale 与 `Instant` 精度一并钉住。
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
        assertThat(alice.version()).as("插入后 @Version 由 provider 从 0 起算").isEqualTo(0L);
        assertThat(alice.address()).isNotNull();
        assertThat(alice.address().street()).isEqualTo("1 Main St");
        assertThat(alice.address().city()).isEqualTo("Springfield");
        assertThat(alice.address().zip()).isEqualTo("11111");
    }

    /// `@Embedded` 整组为空与可空列：`bob` 没有地址，`102` 没有备注——空值必须真的读回 `null`，
    /// 而不是被映射层补成空串/`false`。
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

    /// 订单表：`@ManyToOne` 的 FK 列、`@Convert` 的小写 code、金额与时间列的往返。
    @Test
    @DisplayName("订单表：@ManyToOne 的 FK 与 @Convert 枚举（库里小写 code）正确往返")
    void orderColumnsRoundTripAcrossConvertAndForeignKeys() {
        GoldenSchema.Order order = order(101L);

        assertThat(order.state()).isEqualTo(OrderState.NEW);
        assertThat(order.total()).isEqualByComparingTo(GoldenFixtures.ORDER_101_TOTAL);
        assertThat(order.placedAt()).isEqualTo(GoldenFixtures.ORDER_101_PLACED_AT);
        assertThat(order.note()).isEqualTo("first");
        assertThat(order.customer().name()).isEqualTo("alice");

        assertThat(support()
                        .query(GoldenSchema.Order.class)
                        .orderBy(GoldenSchema.Order::id)
                        .asc()
                        .list())
                .extracting(GoldenSchema.Order::id)
                .containsExactly(101L, 102L, 103L);
        assertThat(order(103L).customer().name()).isEqualTo("bob");
        assertThat(order(103L).state()).isEqualTo(OrderState.SHIPPED);
    }

    /// `@OneToMany` 集合关联：订单 101 两行、102 一行、103 两行；惰性集合必须在事务里可读。
    @Test
    @DisplayName("@OneToMany 集合关联：按订单分组读回子行数")
    void lazyCollectionAssociationsLoadInsideTransaction() {
        Map<Long, Integer> counts = support().inTransaction(() -> {
            Map<Long, Integer> result = new HashMap<>();
            for (GoldenSchema.Order order :
                    support().query(GoldenSchema.Order.class).list()) {
                result.put(order.id(), order.lines().size());
            }
            return result;
        });

        assertThat(counts).containsEntry(101L, 2).containsEntry(102L, 1).containsEntry(103L, 2);
    }

    /// 交付要求里的「不 fetch vs fetch」SQL 条数对照：不 fetch 时 `@ManyToOne` 逐行惰性加载
    /// （1 条主查询 + 每个未命中的关联 1 条），fetch 预取后**只有 1 条**，且两次结果必须相同。
    @Test
    @DisplayName("fetch 预取：不 fetch 多出关联查询，fetch 只有 1 条且结果相同")
    void fetchPrefetchesManyToOneInOneStatement() {
        Observation withoutFetch = support().inTransaction(() -> {
            support().clearSql();
            List<GoldenSchema.Order> orders =
                    support().query(GoldenSchema.Order.class).list();
            List<String> names =
                    orders.stream().map(order -> order.customer().name()).toList();
            return new Observation(names, support().drainSql());
        });

        Observation withFetch = support().inTransaction(() -> {
            support().clearSql();
            List<GoldenSchema.Order> orders = support()
                    .query(GoldenSchema.Order.class)
                    .fetch(GoldenSchema.Order::customer)
                    .list();
            List<String> names =
                    orders.stream().map(order -> order.customer().name()).toList();
            return new Observation(names, support().drainSql());
        });

        assertThat(withFetch.statements()).as("fetch(预加载) 必须一条语句取完").hasSize(1);
        assertThat(withoutFetch.statements()).as("不 fetch 时按行惰性加载，语句数必然更多").hasSizeGreaterThan(1);
        assertThat(withFetch.names())
                .as("预取只改语句数，不改结果")
                .isEqualTo(withoutFetch.names())
                .containsExactly("alice", "alice", "bob");
    }

    /// **契约**（3.1.0 定下，见 USAGE §10.1）：批量条件更新 `update().set().where().execute()` 只写
    /// 调用方声明的列，**不自增也不校验 `@Version`**；乐观锁只在单实体 `update(entity)` 路径生效。
    ///
    /// 需要并发保护时，要么走单实体 `update(entity)`，要么自己在 `where(...)` 里显式带上版本列。
    /// 用例把这条钉成契约而不是缺口：两端（JPA / JDBC）行为一致，改坏了必须有红。
    @Test
    @DisplayName("契约：批量条件更新不触碰 @Version（乐观锁只在单实体 update 路径生效）")
    void bulkConditionalUpdateDoesNotTouchVersion() {
        int affected = support().inTransaction(() -> support()
                .operations(GoldenSchema.Customer.class)
                .update()
                .set(Path.of(GoldenSchema.Customer::creditLimit), new BigDecimal("150.00"))
                .where(GoldenSchema.Customer::id)
                .eq(1L)
                .execute());

        assertThat(affected).isEqualTo(1);
        GoldenSchema.Customer alice = customer(1L);
        assertThat(alice.creditLimit()).isEqualByComparingTo("150.00");
        assertThat(alice.version()).as("契约：批量路径不参与乐观锁，版本号原样保留（JDBC 侧同契约）").isEqualTo(0L);
        assertThat(customer(2L).creditLimit()).isEqualByComparingTo(GoldenFixtures.BOB_CREDIT);
    }

    /// 单实体 `update(entity)`：走乐观锁路径，`@Version` 自增并**回写调用方对象**（接缝承诺）。
    @Test
    @DisplayName("单实体 update：@Version 自增并回写到调用方对象")
    void entityUpdateIncrementsVersionOnCallerObject() {
        GoldenSchema.Customer alice = customer(1L);
        assertThat(alice.version()).isEqualTo(0L);
        support().detach();

        support().inTransaction(() -> {
            support().operations(GoldenSchema.Customer.class).update(alice);
            return null;
        });

        assertThat(alice.version()).as("新版本号必须回写到调用方对象，否则下一次更新撞乐观锁").isEqualTo(1L);
        assertThat(customer(1L).version()).isEqualTo(1L);
    }

    /// 条件 delete：只删匹配行，`execute()` 返回删除行数，其余子行不受影响。
    @Test
    @DisplayName("条件 delete：只删匹配行并返回行数")
    void conditionalDeleteRemovesOnlyMatchingRow() {
        int affected = support().inTransaction(() -> support()
                .operations(GoldenSchema.OrderLine.class)
                .delete()
                .where(GoldenSchema.OrderLine::id)
                .eq(1004L)
                .execute());

        assertThat(affected).isEqualTo(1);
        assertThat(support().query(GoldenSchema.OrderLine.class).list()).hasSize(4);
        assertThat(support()
                        .query(GoldenSchema.OrderLine.class)
                        .where(GoldenSchema.OrderLine::id)
                        .eq(1004L)
                        .list())
                .isEmpty();
    }

    private GoldenSchema.Customer customer(long id) {
        GoldenSchema.Customer customer = support()
                .query(GoldenSchema.Customer.class)
                .where(GoldenSchema.Customer::id)
                .eq(id)
                .first();
        assertThat(customer).as("customer id=%s 必须存在", id).isNotNull();
        return customer;
    }

    private GoldenSchema.Order order(long id) {
        GoldenSchema.Order order = support()
                .query(GoldenSchema.Order.class)
                .where(GoldenSchema.Order::id)
                .eq(id)
                .first();
        assertThat(order).as("order id=%s 必须存在", id).isNotNull();
        return order;
    }

    /// 一次观测：结果（关联上的客户名，按订单顺序）与这次观测发出的 SQL。
    private record Observation(List<String> names, List<String> statements) {}
}
