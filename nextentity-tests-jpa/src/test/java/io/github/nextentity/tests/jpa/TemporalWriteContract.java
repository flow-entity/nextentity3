package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.testing.NullOrdering;
import io.github.nextentity.tests.jpa.support.WriteRoundTripSupport;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 时间类型（`Instant` / `LocalDateTime` / `LocalDate`）的写读契约：**同一套断言、两套连接**。
///
/// 此前非遗留用例里没有任何持久化时间字段（`Instant` / `LocalDate` 在全部非 legacy 的**实体**里 0 命中，
/// 唯一的 `Instant` 字段在 `nextentity-core` 的 builder 单测里，不落库），时间维度只剩 2.2.3 的
/// `InstantLiteralTimezoneIntegrationTest` 一个证据，而那个模块要被移除。本类补的是「移除后仍然有人守着」
/// 这件事，守的具体是三条不同的绑定路径：
///
/// 1. **`Instant` 不得被时区挪动**：库里存的是时刻，绑定时要换算回来。这类缺陷的表现是
///    「存进去 10:15Z，读出来 18:15Z」——语句文本完全正常，只有比时刻才能抓到，所以回读断言的是
///    `Instant` 的**相等**，不是列的文本。
/// 2. **`LocalDateTime` 不得做时区换算**：它没有时区，一旦被当成 `Instant` 处理就会跟着 JVM
///    时区漂移；夹具刻意让同一个实体的 `createdAt` 与 `updatedAt` 落在**不同**墙上时间，
///    这样两者被混用时会立刻现形。
/// 3. **`LocalDate` 不得带时刻**：库列是 `date`，比较与排序都按日历日。
///
/// **oracle 分层**（AGENTS.md「测试编写红线」）：查询结果一律与**内存筛选**对拍
/// （见 `expectedIds`），手算常量只出现在两处已知语义上——闭区间的两端都含、`null` 行不参与
/// 比较。所有时间常量都取整秒，小数秒精度单独由 [TemporalWriteContract#preservesSubSecondPrecision]
/// 用 `LocalDateTime` 钉（`Instant` 的小数位精度会撞上 `instantBindingKeepsTheInstant` 说明的
/// PG 兼容模式差异，两者不混在一起）。
///
/// **时区**：默认把 JVM 时区钉成 `+08:00`（见 [TemporalWriteContract#resetBefore]），这样 `Instant`
/// 的断言在任何跑测机器上都保留区分力。要验证「换时区仍然对」，**整通道**跑一次：
/// `./mvnw -pl nextentity-tests-jpa test -Dnextentity.pinTimezone=false -DargLine=-Duser.timezone=America/New_York`
/// ——不能在单个用例里改默认时区，理由（H2/Hibernate 缓存了建连时的时区）写在同一处注释里。
abstract class TemporalWriteContract {

    /// 行 1 的时刻：三条列各不相同，便于发现「用错列/用错绑定路径」。
    static final Instant CREATED_1 = Instant.parse("2024-03-01T10:15:30Z");

    static final LocalDateTime UPDATED_1 = LocalDateTime.of(2024, 3, 1, 18, 15, 30);
    static final LocalDate DUE_1 = LocalDate.of(2024, 3, 10);

    /// 行 2 的时刻：跨月，区间与排序才有区分力。
    static final Instant CREATED_2 = Instant.parse("2024-04-01T10:15:30Z");

    static final LocalDateTime UPDATED_2 = LocalDateTime.of(2024, 4, 1, 18, 15, 30);
    static final LocalDate DUE_2 = LocalDate.of(2024, 4, 10);

    /// 夹具的内存副本：差分 oracle 的输入，与灌进库里的那三行逐字段相同。
    private List<TemporalItemEntity> fixture = List.of();

    /// 跑测机器原来的默认时区，`@AfterEach` 负责还原。
    private TimeZone originalTimeZone;

    /// 被测装置：子类只决定接哪条库。
    protected abstract WriteRoundTripSupport<TemporalItemEntity> support();

    /// 该后端在 `ASC` 时把 `null` 放在哪一侧。
    ///
    /// 时间列的 `null` 位置是**方言事实**（H2 的 MySQL 兼容模式与真 MySQL 都是 `NULLS_FIRST`，
    /// H2 的 PostgreSQL 兼容模式是 `NULLS_LAST`），不是框架语义，所以由子类声明而不是写死；
    /// 内存 oracle 里比时间用的比较器也由它决定，于是「方言事实」与「用例期望」只有一个出处。
    protected abstract NullOrdering nullOrdering();

    @BeforeEach
    void resetBefore() {
        // 钉住 JVM 默认时区：`Instant` 的区分力全部来自「墙钟被挪过就会差整小时」，跑测机器若在
        // UTC 上，这条断言对「按 JVM 时区换算」类缺陷的区分力归零。还原见 [TemporalWriteContract#resetAfter]。
        //
        // 钉的是**建 SessionFactory 之前**就该定下的值：H2/Hibernate 会把默认时区缓存进连接/类型绑定，
        // 所以「harness 建好之后再 `TimeZone.setDefault`」不是合法的时区实验——实测（`hibernate`
        // 直调 `em.persist`、完全不经过 nextentity）会把 `LocalDate.of(2024,3,10)` 写成 `2024-03-09`，
        // 差的正好是切换前后两个时区的偏移；而整个 JVM 一开始就在负偏移时区下则完全正常。
        // 想验证「换时区仍然对」请整通道跑，见 `nextentity.pinTimezone=false` 的说明。
        originalTimeZone = TimeZone.getDefault();
        if (!"false".equalsIgnoreCase(System.getProperty("nextentity.pinTimezone", "true"))) {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        }
        resetRows();
    }

    @AfterEach
    void resetAfter() {
        resetRows();
        if (originalTimeZone != null) {
            TimeZone.setDefault(originalTimeZone);
        }
    }

    @Test
    @DisplayName("insert/reload：LocalDateTime 与 LocalDate 原样回读")
    void insertAndReloadPreserveWallClockAndDate() {
        insert(row(1L, "first", CREATED_1, UPDATED_1, DUE_1));

        TemporalItemEntity reloaded = reload(1L);
        assertThat(reloaded.updatedAt()).as("LocalDateTime 是无时区的墙上时间，不得被换算").isEqualTo(UPDATED_1);
        assertThat(reloaded.dueDate()).as("LocalDate 落 date 列，不得带出时刻").isEqualTo(DUE_1);
    }

    @Test
    @DisplayName("insert/reload：Instant 回到同一个时刻，不被时区挪动")
    void insertAndReloadPreserveTheInstant() {
        insert(row(1L, "first", CREATED_1, UPDATED_1, DUE_1));

        assertInstantRoundTrip(reload(1L).createdAt(), CREATED_1, "Instant 必须回到同一个时刻：被 JVM 时区挪过就会差出整小时");
    }

    @Test
    @DisplayName("insert/reload：LocalDateTime 的小数秒不丢")
    void preservesSubSecondPrecision() {
        LocalDateTime precise = LocalDateTime.of(2024, 6, 1, 18, 15, 30, 123_456_000);

        insert(row(1L, "sub", CREATED_1, precise, DUE_1));

        assertThat(reload(1L).updatedAt()).as("微秒级小数秒：列精度不足或绑定截断都会在这里露出来").isEqualTo(precise);
    }

    @Test
    @DisplayName("查询：Instant 等值条件与内存筛选一致")
    void queryByInstantEqualityMatchesSingleRow() {
        seedThreeRows();

        assertThat(ids(query().where(TemporalItemEntity::createdAt)
                        .eq(CREATED_1)
                        .orderBy(TemporalItemEntity::id)
                        .asc()
                        .list()))
                .as("created_at = %s", CREATED_1)
                .isEqualTo(expectedIds(row -> CREATED_1.equals(row.createdAt())));

        assertThat(ids(query().where(TemporalItemEntity::createdAt)
                        .eq(CREATED_2)
                        .orderBy(TemporalItemEntity::id)
                        .asc()
                        .list()))
                .as("created_at = %s", CREATED_2)
                .isEqualTo(expectedIds(row -> CREATED_2.equals(row.createdAt())));

        // 已知语义：库里没有的时刻不命中任何行（也证明等值条件没被丢掉）
        assertThat(ids(query().where(TemporalItemEntity::createdAt)
                        .eq(Instant.parse("2025-01-01T00:00:00Z"))
                        .list()))
                .isEmpty();
    }

    @Test
    @DisplayName("查询：Instant 区间与内存筛选一致，且闭区间两端都含")
    void queryByInstantBetweenIsClosedOnBothEnds() {
        seedThreeRows();

        assertThat(ids(query().where(TemporalItemEntity::createdAt)
                        .between(CREATED_1, CREATED_2)
                        .orderBy(TemporalItemEntity::id)
                        .asc()
                        .list()))
                .as("between 两端都是夹具里的时刻，两端行都必须命中（闭区间）")
                .isEqualTo(expectedIds(row -> row.createdAt() != null
                        && !row.createdAt().isBefore(CREATED_1)
                        && !row.createdAt().isAfter(CREATED_2)))
                .containsExactly(1L, 2L);

        assertThat(ids(query().where(TemporalItemEntity::createdAt)
                        .gt(CREATED_1)
                        .orderBy(TemporalItemEntity::id)
                        .asc()
                        .list()))
                .as("严格大于：左端点自己不算")
                .isEqualTo(expectedIds(
                        row -> row.createdAt() != null && row.createdAt().isAfter(CREATED_1)))
                .containsExactly(2L);
    }

    @Test
    @DisplayName("查询：LocalDateTime 与 LocalDate 的比较各走各的列，le/lt 的边界严格性可区分")
    void queryByLocalDateTimeAndLocalDateComparison() {
        seedThreeRows();

        assertThat(ids(query().where(TemporalItemEntity::updatedAt)
                        .ge(UPDATED_2)
                        .orderBy(TemporalItemEntity::id)
                        .asc()
                        .list()))
                .as("updated_at >= %s（边界就是夹具值，边界行必须含）", UPDATED_2)
                .isEqualTo(expectedIds(
                        row -> row.updatedAt() != null && !row.updatedAt().isBefore(UPDATED_2)))
                .containsExactly(2L);

        // le / lt 的边界值刻意取夹具里的日期：上界若不是夹具值，`<=` 被改成 `<` 返回集合同样不变，
        // 断言就对「闭/开区间被写反」完全没有区分力（这正是「手算常量钉已知语义」要钉的东西）。
        assertThat(ids(query().where(TemporalItemEntity::dueDate)
                        .le(DUE_1)
                        .orderBy(TemporalItemEntity::id)
                        .asc()
                        .list()))
                .as("due_date <= %s：边界行 1 必须含", DUE_1)
                .isEqualTo(expectedIds(
                        row -> row.dueDate() != null && !row.dueDate().isAfter(DUE_1)))
                .containsExactly(1L);

        assertThat(ids(query().where(TemporalItemEntity::dueDate)
                        .lt(DUE_1)
                        .orderBy(TemporalItemEntity::id)
                        .asc()
                        .list()))
                .as("due_date < %s：严格小于时边界行 1 自己不算", DUE_1)
                .isEqualTo(expectedIds(
                        row -> row.dueDate() != null && row.dueDate().isBefore(DUE_1)))
                .isEmpty();

        assertThat(ids(query().where(TemporalItemEntity::dueDate)
                        .lt(DUE_2)
                        .orderBy(TemporalItemEntity::id)
                        .asc()
                        .list()))
                .as("due_date < %s：只差一行的严格比较也要能分出 1", DUE_2)
                .isEqualTo(expectedIds(
                        row -> row.dueDate() != null && row.dueDate().isBefore(DUE_2)))
                .containsExactly(1L);

        assertThat(ids(query().where(TemporalItemEntity::dueDate)
                        .between(DUE_1, DUE_2)
                        .orderBy(TemporalItemEntity::id)
                        .asc()
                        .list()))
                .as("日期区间同样是闭的")
                .isEqualTo(expectedIds(row -> row.dueDate() != null
                        && !row.dueDate().isBefore(DUE_1)
                        && !row.dueDate().isAfter(DUE_2)))
                .containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("查询：时间为 null 的行能被 isNull / isNotNull 分别选出")
    void queryByNullTemporal() {
        seedThreeRows();

        assertThat(ids(query().where(TemporalItemEntity::createdAt)
                        .isNull()
                        .orderBy(TemporalItemEntity::id)
                        .asc()
                        .list()))
                .as("is null：与内存筛选对拍")
                .isEqualTo(expectedIds(row -> row.createdAt() == null));

        assertThat(ids(query().where(TemporalItemEntity::createdAt)
                        .isNotNull()
                        .orderBy(TemporalItemEntity::id)
                        .asc()
                        .list()))
                .as("is not null：与内存筛选对拍，且与 is null 互补")
                .isEqualTo(expectedIds(row -> row.createdAt() != null));
    }

    @Test
    @DisplayName("排序：按 Instant 升/降序与内存排序一致，null 落在方言规定的一侧")
    void orderingByInstantFollowsChronology() {
        seedThreeRows();

        Comparator<TemporalItemEntity> byCreatedAt = Comparator.comparing(
                TemporalItemEntity::createdAt,
                nullOrdering() == NullOrdering.NULLS_FIRST
                        ? Comparator.nullsFirst(Comparator.<Instant>naturalOrder())
                        : Comparator.nullsLast(Comparator.<Instant>naturalOrder()));

        assertThat(ids(query().orderBy(TemporalItemEntity::createdAt).asc().list()))
                .as("created_at 升序（%s）：与内存排序对拍", nullOrdering())
                .isEqualTo(expectedIds(row -> true, byCreatedAt));

        assertThat(ids(query().orderBy(TemporalItemEntity::createdAt).desc().list()))
                .as("created_at 降序：null 的相对位置跟着翻面")
                .isEqualTo(expectedIds(row -> true, byCreatedAt.reversed()));
    }

    @Test
    @DisplayName("update：三种时间列都进 SET 子句，且新值真的落库")
    void updateWritesAllTemporalColumns() {
        insert(row(1L, "first", CREATED_1, UPDATED_1, DUE_1));
        support().clear();
        support().clearSql();

        LocalDate newDue = LocalDate.of(2024, 5, 20);
        update(row(1L, "first", CREATED_2, UPDATED_2, newDue));

        List<String> updates = statements("update temporal_item");
        assertThat(updates).hasSize(1);
        assertThat(setClause(updates.getFirst()))
                .as("三个时间列都必须进 SET：漏掉一列就等于该列永远改不了")
                .contains("created_at=?")
                .contains("updated_at=?")
                .contains("due_date=?");

        TemporalItemEntity reloaded = reload(1L);
        assertThat(reloaded).as("行必须还在").isNotNull();
        assertInstantRoundTrip(reloaded.createdAt(), CREATED_2, "Instant 列也必须真的换成新值");
        assertThat(reloaded.updatedAt()).isEqualTo(UPDATED_2);
        assertThat(reloaded.dueDate()).isEqualTo(newDue);
    }

    /// 灌三行：前两行时间互不相同，第三行三列全 null。
    private void seedThreeRows() {
        fixture = List.of(
                row(1L, "first", CREATED_1, UPDATED_1, DUE_1),
                row(2L, "second", CREATED_2, UPDATED_2, DUE_2),
                // 三列全 null：排序、isNull、区间都必须把它排除在比较之外
                row(3L, "nulls", null, null, null));
        insert(fixture.toArray(TemporalItemEntity[]::new));
    }

    /// 该通道下 `Instant` 的往返是否保持时刻不变。
    ///
    /// H2 的 PostgreSQL 兼容模式配上钉死的 `PostgreSQLDialect` 时，`Instant` 会被绑成丢偏移的
    /// `timestamp`：写进去 `10:15:30Z`、读回来 `02:15:30Z`（正好差一个 JVM 时区）。这不是框架缺陷
    /// ——同一个实体在 PG 模式下**不走 nextentity**、直接 `em.persist` + `em.find` 也差 8 小时，
    /// 且两种模式建出的列都是 `timestamp with time zone(6)`（真 PostgreSQL 的 `timestamptz` 存的是
    /// 时刻，没有这个偏移）。
    ///
    /// 因此 `Instant` 的往返断言走 [TemporalWriteContract#assertInstantRoundTrip]：能保时刻的通道钉
    /// **相等**（这是区分「按 JVM 时区换算」的唯一判据），PG 兼容模式钉「非 null 且已被换成新值」。
    /// 不用 `assumeTrue` 跳过——跳过会让该通道少一条证据且不进任何计数，而「Instant 列到底写没写进去」
    /// 在两种模式下都能判。
    private static boolean instantBindingKeepsTheInstant() {
        return !"postgresql".equalsIgnoreCase(System.getProperty("nextentity.h2mode", "mysql"));
    }

    /// `Instant` 往返的断言形态按通道分派，理由见 [TemporalWriteContract#instantBindingKeepsTheInstant]。
    private static void assertInstantRoundTrip(Instant actual, Instant expected, String message) {
        if (instantBindingKeepsTheInstant()) {
            assertThat(actual).as(message).isEqualTo(expected);
        } else {
            assertThat(actual)
                    .as(message + "（PG 兼容模式的绑定丢偏移，本通道只钉「列确实被写过且仍是同一时刻附近」）")
                    .isNotNull();
            assertThat(Math.abs(Duration.between(expected, actual).toHours()))
                    .as("%s：只容忍时区偏移量级的漂移，超出说明写错了列或根本没落库", message)
                    .isLessThan(24);
        }
    }

    /// 差分 oracle：在内存里对**同一批夹具行**做同样的筛选与排序。
    ///
    /// 这是本类正确性证据的正体——期望值不写死，而是从夹具现算，于是「框架把条件编译错了」
    /// 与「我的常量算错了」不会互相掩盖。
    private List<Long> expectedIds(Predicate<TemporalItemEntity> filter, Comparator<TemporalItemEntity> order) {
        return fixture.stream()
                .filter(filter)
                .sorted(order)
                .map(TemporalItemEntity::id)
                .toList();
    }

    /// 同 [TemporalWriteContract#expectedIds]，但只按主键升序（查询一律带 `orderBy(id).asc()`）。
    private List<Long> expectedIds(Predicate<TemporalItemEntity> filter) {
        return expectedIds(filter, Comparator.comparing(TemporalItemEntity::id));
    }

    private static TemporalItemEntity row(
            long id, String name, Instant createdAt, LocalDateTime updatedAt, LocalDate dueDate) {
        return new TemporalItemEntity(id, name, createdAt, updatedAt, dueDate);
    }

    private void insert(TemporalItemEntity... rows) {
        support().inTransaction(entityManager -> operations().insertAll(List.of(rows)));
        support().clear();
    }

    private void update(TemporalItemEntity row) {
        support().inTransaction(entityManager -> operations().update(row));
        support().clear();
    }

    private TemporalItemEntity reload(long id) {
        return support().inTransaction(() -> support().entityManager().find(TemporalItemEntity.class, id));
    }

    private EntityOperations<TemporalItemEntity> operations() {
        return support().operations();
    }

    private EntityQuery<TemporalItemEntity> query() {
        return operations().query();
    }

    private void resetRows() {
        fixture = List.of();
        support().truncate("TemporalItemEntity");
        support().clear();
        support().clearSql();
    }

    private static List<Long> ids(List<TemporalItemEntity> rows) {
        return rows.stream().map(TemporalItemEntity::id).toList();
    }

    /// 取归一化语句里 `set` 与 `where` 之间的片段。
    private static String setClause(String statement) {
        int set = statement.indexOf(" set ");
        int where = statement.indexOf(" where ", set + 5);
        return statement.substring(set + 5, where < 0 ? statement.length() : where);
    }

    /// 归一化后按前缀筛出被测语句。
    private List<String> statements(String prefix) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        return support().drainSql().stream()
                .map(WriteRoundTripSupport::normalized)
                .filter(statement -> statement.startsWith(lowered))
                .toList();
    }
}
