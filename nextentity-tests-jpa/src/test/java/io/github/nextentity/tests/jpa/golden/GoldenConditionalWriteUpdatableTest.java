package io.github.nextentity.tests.jpa.golden;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.Path;
import io.github.nextentity.testing.golden.GoldenFixtures;
import io.github.nextentity.testing.golden.GoldenSchema;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/// `@Column(updatable = false)` 列的条件写：JPA 与 JDBC 必须**同一句话拒绝**，不许一边拒绝、
/// 一边把只读列真写进库。
///
/// 对拍口径来自 JDBC 侧 `ConditionalSql`（golden 契约 `conditionalUpdateOfNonUpdatableColumnIsRejected`
/// 断言同一段文案）：文案逐字相同，只差 path 前缀。golden schema 的 `golden_order.note` 就是
/// `@Column(name = "note", updatable = false)`，所以这里能直接用同一张表对拍。
///
/// oracle 分层：拒绝文案是手算常量（钉「两后端说的是同一件事」）；正确性证据是拒绝后**读回原值不变**
/// （没写下去）与可更新列照常生效（拒绝不是把整个 UPDATE 能力误伤）。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoldenConditionalWriteUpdatableTest {

    private static final long ORDER_ID = 101L;

    private final JpaGoldenSupport support = new JpaGoldenSupport("golden_conditional_write_updatable");

    @BeforeEach
    void seed() {
        support.clear();
        support.seedGolden();
    }

    @AfterEach
    void reset() {
        support.clear();
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }

    /// 只读列被 `set(...)` 时必须抛 UOE，且文案与 JDBC 后端逐字一致；库里的值不许动。
    @Test
    @DisplayName("只读列条件更新：与 JDBC 逐字相同的 UOE，且库里的值不变")
    void conditionalUpdateOfReadOnlyColumnIsRejected() {
        // 不包事务：拒绝发生在拼 SQL 之前（`ConditionalWrite.requireUpdatable` 先于 `createQuery`），
        // 而 `HibernateHarness.inTransaction` 没有回滚，异常穿出去会把装置留在「事务已开启」状态。
        assertThatThrownBy(() -> support.operations(GoldenSchema.Order.class)
                        .update()
                        .set(Path.of(GoldenSchema.Order::note), "changed")
                        .where(GoldenSchema.Order::id)
                        .eq(ORDER_ID)
                        .execute())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("条件更新不支持 @Column(updatable=false) 列: " + GoldenSchema.Order.class.getName() + ".note");

        assertThat(order(ORDER_ID).note()).as("拒绝必须在拼 SQL 之前发生，库里的 note 保持原值").isEqualTo("first");
    }

    /// 反向对照：同一张表、同一条 UPDATE 路径，可更新列必须照常写——否则「一律拒绝」也能骗过上面那条。
    @Test
    @DisplayName("可更新列条件更新：照常执行并返回受影响行数")
    void conditionalUpdateOfWritableColumnSucceeds() {
        int affected = support.<Integer>inTransaction(() -> support.operations(GoldenSchema.Order.class)
                .update()
                .set(Path.of(GoldenSchema.Order::total), new BigDecimal("250.00"))
                .where(GoldenSchema.Order::id)
                .eq(ORDER_ID)
                .execute());

        assertThat(affected).isEqualTo(1);
        assertThat(order(ORDER_ID).total()).isEqualByComparingTo("250.00");
        assertThat(order(ORDER_ID).note()).isEqualTo("first");
        assertThat(order(ORDER_ID).total()).isNotEqualByComparingTo(GoldenFixtures.ORDER_101_TOTAL);
    }

    private GoldenSchema.Order order(long id) {
        return support.query(GoldenSchema.Order.class)
                .where(GoldenSchema.Order::id)
                .eq(id)
                .first();
    }
}
