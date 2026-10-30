package io.github.nextentity.testing.golden;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/// golden schema 的固定夹具：三张表的行，两个后端（JPA / JDBC）与各方言通道共用同一份数据。
///
/// 期望值全部写成常量（与 [GoldenSchema] 的字段一一对应），落地用例的断言就是拿常量对读回值——
/// 手算常量在这里只钉「写入什么、读回什么」的映射语义；正确性证据来自「同一份夹具在 H2 / MySQL /
/// PostgreSQL / SQL Server 四条通道上都读出同样的值」（跨方言对拍），以及 JPA 与 JDBC 两条实现路径
/// 对同一份夹具给出同样结果（差分）。
///
/// 刻意保留的空值/精度/时区边界：`bob` 的地址是 `null`（`@Embedded` 整组为空）、`102` 的备注是
/// `null`、`1002` 未发货（`shipped_at` 为 `null`）、金额保留两位小数（`DECIMAL(12,2)` 的 scale
/// 必须原样读回）、时间用 `Instant`（带时区，跨方言存储形态不同但读回值必须相等）。
///
/// [#data()] 每次返回**全新实例**且三张表之间引用的是同一批对象（父引用的就是本批次里的父），
/// 这样 `persist`/`insert` 会回填 `@Version` 之类的字段，也不会把上一次用例的状态带进下一次。
public final class GoldenFixtures {

    /// alice：BASIC / 100.00 / 有效 / 有地址。
    public static final BigDecimal ALICE_CREDIT = new BigDecimal("100.00");

    /// bob：PRO / 250.50 / 停用 / 地址为空。
    public static final BigDecimal BOB_CREDIT = new BigDecimal("250.50");

    /// carol：ENTERPRISE / 999.99 / 有效 / 有地址。
    public static final BigDecimal CAROL_CREDIT = new BigDecimal("999.99");

    /// 订单 101 的总计：2 × 50.00 + 1 × 12.25。
    public static final BigDecimal ORDER_101_TOTAL = new BigDecimal("112.25");

    /// 订单 102 的总计：3 × 13.50。
    public static final BigDecimal ORDER_102_TOTAL = new BigDecimal("40.50");

    /// 订单 103 的总计：1 × 60.00 + 2 × 30.00。
    public static final BigDecimal ORDER_103_TOTAL = new BigDecimal("120.00");

    /// alice 的入会时刻（`Instant`：存储形态随时区变，读回值不变）。
    public static final Instant ALICE_JOINED_AT = Instant.parse("2024-01-02T03:04:05Z");

    /// carol 的入会时刻（与 alice 差两天，排序/区间断言都可用）。
    public static final Instant CAROL_JOINED_AT = Instant.parse("2024-01-04T05:06:07Z");

    /// bob 的入会时刻。
    public static final Instant BOB_JOINED_AT = Instant.parse("2024-01-03T00:00:00Z");

    /// 订单 101 的下单时刻（`LocalDateTime`：不带时区）。
    public static final LocalDateTime ORDER_101_PLACED_AT = LocalDateTime.of(2024, 1, 2, 3, 4, 5);

    /// 订单 102 的下单时刻。
    public static final LocalDateTime ORDER_102_PLACED_AT = LocalDateTime.of(2024, 1, 5, 10, 15);

    /// 订单 103 的下单时刻。
    public static final LocalDateTime ORDER_103_PLACED_AT = LocalDateTime.of(2024, 2, 1, 8, 0);

    /// 订单行 1001 的发货时刻（与 bob 的入会时刻差一秒，避免重复字面量）。
    public static final Instant LINE_1001_SHIPPED_AT = Instant.parse("2024-01-03T00:00:01Z");

    private GoldenFixtures() {}

    /// 一次完整的 golden 夹具：三张表的行，父子引用同一批对象。
    ///
    /// @param customers 三个客户（主键 1/2/3）
    /// @param orders    三张订单（101/102/103）
    /// @param lines     五行订单行（1001…1005）
    public record GoldenData(
            List<GoldenSchema.Customer> customers,
            List<GoldenSchema.Order> orders,
            List<GoldenSchema.OrderLine> lines) {}

    /// 构造一次夹具：每次调用都是全新实例，父子引用一致。
    ///
    /// @return 三张表的行；可直接交给 `persist` / `insertAll`
    public static GoldenData data() {
        List<GoldenSchema.Customer> customers = List.of(
                new GoldenSchema.Customer(
                        1L,
                        "alice",
                        GoldenSchema.Tier.BASIC,
                        ALICE_CREDIT,
                        true,
                        ALICE_JOINED_AT,
                        new GoldenSchema.Address("1 Main St", "Springfield", "11111")),
                new GoldenSchema.Customer(2L, "bob", GoldenSchema.Tier.PRO, BOB_CREDIT, false, BOB_JOINED_AT, null),
                new GoldenSchema.Customer(
                        3L,
                        "carol",
                        GoldenSchema.Tier.ENTERPRISE,
                        CAROL_CREDIT,
                        true,
                        CAROL_JOINED_AT,
                        new GoldenSchema.Address("9 Oak Ave", "Shelbyville", "22222")));

        Map<Long, GoldenSchema.Customer> byId =
                customers.stream().collect(Collectors.toMap(GoldenSchema.Customer::id, Function.identity()));

        List<GoldenSchema.Order> orders = List.of(
                new GoldenSchema.Order(
                        101L, byId.get(1L), GoldenSchema.OrderState.NEW, ORDER_101_TOTAL, ORDER_101_PLACED_AT, "first"),
                new GoldenSchema.Order(
                        102L, byId.get(1L), GoldenSchema.OrderState.PAID, ORDER_102_TOTAL, ORDER_102_PLACED_AT, null),
                new GoldenSchema.Order(
                        103L,
                        byId.get(2L),
                        GoldenSchema.OrderState.SHIPPED,
                        ORDER_103_TOTAL,
                        ORDER_103_PLACED_AT,
                        "third"));

        Map<Long, GoldenSchema.Order> ordersById =
                orders.stream().collect(Collectors.toMap(GoldenSchema.Order::id, Function.identity()));

        List<GoldenSchema.OrderLine> lines = List.of(
                new GoldenSchema.OrderLine(
                        1001L, ordersById.get(101L), 1, 2, new BigDecimal("50.00"), true, LINE_1001_SHIPPED_AT),
                new GoldenSchema.OrderLine(1002L, ordersById.get(101L), 2, 1, new BigDecimal("12.25"), false, null),
                new GoldenSchema.OrderLine(
                        1003L,
                        ordersById.get(102L),
                        1,
                        3,
                        new BigDecimal("13.50"),
                        true,
                        Instant.parse("2024-01-06T09:30:00Z")),
                new GoldenSchema.OrderLine(1004L, ordersById.get(103L), 1, 1, new BigDecimal("60.00"), false, null),
                new GoldenSchema.OrderLine(
                        1005L,
                        ordersById.get(103L),
                        2,
                        2,
                        new BigDecimal("30.00"),
                        true,
                        Instant.parse("2024-02-02T12:00:00Z")));

        return new GoldenData(customers, orders, lines);
    }
}
