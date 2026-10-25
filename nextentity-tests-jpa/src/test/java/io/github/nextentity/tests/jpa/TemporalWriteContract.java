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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

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
/// **时区**：JVM 默认时区由 surefire/failsafe 的 `-Duser.timezone` 钉成 `+08:00`
/// （`nextentity-tests-jpa/pom.xml` 的 `pinned.timezone`），这样 `Instant` 的断言在任何跑测机器上都
/// 保留区分力。**不能**在用例里改：Hibernate 在 `buildSessionFactory()` 时就缓存了默认时区，而装置由
/// 子类字段初始化创建，早于任何 `@BeforeEach`——在 `@BeforeEach` 里 `TimeZone.setDefault` 是「晚于
/// 建连」的，实测会把 `LocalDate.of(2024,3,10)` 写成 `2024-03-09`，等于把装置搞成非法的时区实验。
/// 钉没钉上是可断言的事实（见 [TemporalWriteContract#resetBefore]）。要验证「换时区仍然对」，
/// **整通道**跑一次：`./mvnw -pl nextentity-tests-jpa test -DargLine=-Duser.timezone=America/New_York`。
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

    /// 装置钉住的 JVM 时区偏移：与 pom 的 `pinned.timezone` 是同一个值，`resetBefore` 拿它自检。
    static final ZoneOffset PINNED_OFFSET = ZoneOffset.ofHours(8);

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
        // 时区由 pom 的 `-Duser.timezone` 在 fork 启动时钉住（见类注释），这里把它变成**可断言的
        // 事实**：钉不上就等于所有 `Instant` 断言在 UTC 机器上失去区分力，那种「绿」没有意义。
        assertThat(ZoneId.systemDefault().getRules().getOffset(CREATED_1))
                .as("JVM 时区必须被 pom 的 -Duser.timezone 钉在 %s（见 nextentity-tests-jpa/pom.xml）", PINNED_OFFSET)
                .isEqualTo(PINNED_OFFSET);
        resetRows();
    }

    @AfterEach
    void resetAfter() {
        resetRows();
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
    @DisabledIfSystemProperty(
            named = "nextentity.h2mode",
            matches = "postgresql",
            disabledReason = "H2 的 PG 兼容模式把 Instant 绑成丢偏移的 timestamp，本通道的形态由"
                    + " insertAndReloadLoseExactlyOneJvmOffsetOnPostgresCompatibleMode 单独钉")
    @DisplayName("insert/reload：Instant 回到同一个时刻，不被时区挪动")
    void insertAndReloadPreserveTheInstant() {
        insert(row(1L, "first", CREATED_1, UPDATED_1, DUE_1));

        TemporalItemEntity reloaded = reload(1L);
        assertThat(reloaded).as("行必须还在").isNotNull();
        assertThat(reloaded.createdAt())
                .as("Instant 必须回到同一个时刻：被 JVM 时区挪过就会差出整小时")
                .isEqualTo(CREATED_1);
    }

    /// PG 兼容模式的 `Instant` 形态：钉「丢掉的偏移**恰好**是一个 JVM 时区偏移」。
    ///
    /// 这条只在 opt-in 通道跑，且用显式注解 + `@Tag` 标记（AGENTS.md「方言跳过必须可数」），
    /// 不用 `assumeTrue`：跳过不进任何计数，而这里要证明的恰恰是「该通道上偏移是 8 小时而不是别的量」。
    /// 断言不留容忍带——写成「漂移小于 24 小时」会把所有合法时区差（UTC−12…UTC+14）都放过去，
    /// 对这个用例唯一要抓的缺陷类别（时刻被时区挪动）零区分力。
    @Test
    @EnabledIfSystemProperty(named = "nextentity.h2mode", matches = "postgresql")
    @Tag("dialect-h2-postgresql")
    @DisplayName("insert/reload（PG 兼容模式）：Instant 丢掉的偏移恰好是一个 JVM 时区偏移")
    void insertAndReloadLoseExactlyOneJvmOffsetOnPostgresCompatibleMode() {
        insert(row(1L, "first", CREATED_1, UPDATED_1, DUE_1));

        TemporalItemEntity reloaded = reload(1L);
        assertThat(reloaded).as("行必须还在").isNotNull();
        long drift = Duration.between(CREATED_1, reloaded.createdAt()).getSeconds();
        long offset = ZoneId.systemDefault().getRules().getOffset(CREATED_1).getTotalSeconds();
        assertThat(drift)
                .as("%s（丢的必须恰好是一个 JVM 时区偏移：偏移量变了、方向反了、或哪天不丢了都要红）", reloaded.createdAt())
                .isEqualTo(-offset);
    }

    @Test
    @DisplayName("insert/reload：LocalDateTime 的小数秒不丢")
    void preservesSubSecondPrecision() {
        // 钉毫秒而不是微秒：这条要证明的是「绑定不把小数秒截到整秒」，而列精度是方言映射的
        // 默认值（H2 `timestamp(6)` / MySQL `datetime(6)`），不是框架语义——用微秒会把
        // 「Hibernate 哪天把默认精度调小」也算成红，那是装置事实，不是本用例要守的东西。
        LocalDateTime precise = LocalDateTime.of(2024, 6, 1, 18, 15, 30, 123_000_000);

        insert(row(1L, "sub", CREATED_1, precise, DUE_1));

        TemporalItemEntity reloaded = reload(1L);
        assertThat(reloaded).as("行必须还在").isNotNull();
        assertThat(reloaded.updatedAt()).as("毫秒级小数秒：绑定截断到整秒会在这里露出来").isEqualTo(precise);
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
        assertThat(reloaded.createdAt())
                .as("Instant 列也必须真的换成新值（期望值按通道算，见 storedInstant）")
                .isEqualTo(storedInstant(CREATED_2));
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

    /// 该通道下 `Instant` 写进库再读回来之后的**精确**期望值。
    ///
    /// 默认恒等：能保时刻的通道（H2 的 MySQL 兼容模式、真 MySQL）必须原样回来，这是区分
    /// 「按 JVM 时区换算」的唯一判据。H2 的 PostgreSQL 兼容模式配上钉死的 `PostgreSQLDialect` 时，
    /// `Instant` 会被绑成丢偏移的 `timestamp`：写 `10:15:30Z`、读回 `02:15:30Z`（实测漂移
    /// `-28800` 秒 = `-1 × +08:00` 偏移）。这不是框架缺陷——同一个实体在该模式下**不走 nextentity**、
    /// 直接 `em.persist` + `em.find` 也差 8 小时，且两种模式建出的列都是
    /// `timestamp with time zone(6)`（真 PostgreSQL 的 `timestamptz` 存时刻，没有这个偏移）。
    ///
    /// 所以这里把「方言事实」与「用例期望」收敛到一处：期望值按通道现算，断言一律是**相等**，
    /// 没有容忍带；该通道的偏移量本身另由
    /// [TemporalWriteContract#insertAndReloadLoseExactlyOneJvmOffsetOnPostgresCompatibleMode] 钉死。
    private Instant storedInstant(Instant value) {
        if (instantRoundTripKeepsTheInstant()) {
            return value;
        }
        return value.minusSeconds(
                ZoneId.systemDefault().getRules().getOffset(value).getTotalSeconds());
    }

    /// 该通道下 `Instant` 的往返是否保持时刻不变。
    ///
    /// 与 [TemporalWriteContract#nullOrdering] 同一套写法：方言事实由子类声明，基类不读系统属性，
    /// 于是「后端中立」这条在契约基类上仍然成立。
    protected boolean instantRoundTripKeepsTheInstant() {
        return true;
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
