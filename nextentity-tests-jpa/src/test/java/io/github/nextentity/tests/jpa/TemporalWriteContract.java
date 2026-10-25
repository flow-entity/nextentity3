package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.testing.NullOrdering;
import io.github.nextentity.tests.jpa.support.WriteRoundTripSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 时间类型（`Instant` / `LocalDateTime` / `LocalDate`）的写读契约：**同一套断言、两套连接**。
///
/// 此前非遗留用例里没有任何时间字段（`Instant` / `LocalDate` 在全部非 legacy 测试实体里 0 命中），
/// 时间维度只剩 2.2.3 的 `InstantLiteralTimezoneIntegrationTest` 一个证据，而那个模块要被移除。
/// 本类补的是「移除后仍然有人守着」这件事，守的具体是三条不同的绑定路径：
///
/// 1. **`Instant` 不得被时区挪动**：库里存的是 UTC 归一化后的时刻，绑定时要换算回来。这类
///    缺陷的表现是「存进去 10:15Z，读出来 18:15Z」——语句文本完全正常，只有比时刻才能抓到，
///    所以回读断言的是 `Instant` 的**相等**，不是列的文本。
/// 2. **`LocalDateTime` 不得做时区换算**：它没有时区，一旦被当成 `Instant` 处理就会跟着 JVM
///    时区漂移；夹具刻意让同一个实体的 `createdAt` 与 `updatedAt` 落在**不同**墙上时间，
///    这样两者被混用时会立刻现形。
/// 3. **`LocalDate` 不得带时刻**：库列是 `date`，比较与排序都按日历日。
///
/// **oracle 分层**（AGENTS.md「测试编写红线」）：查询结果一律与**内存筛选**对拍
/// （见 `expectedIds`），手算常量只出现在两处已知语义上——闭区间的两端都含、`null` 行不参与
/// 比较。所有时间常量都取整秒：库列的小数位精度（H2 与 MySQL 的 `timestamp` 不同）与
/// 「今天是几号」都不该成为本用例的自由变量。
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
        resetRows();
    }

    @AfterEach
    void resetAfter() {
        resetRows();
    }

    @Test
    @DisplayName("insert/reload：三种时间类型原样回读，Instant 不被时区挪动")
    void insertAndReloadPreserveTemporalValues() {
        insert(row(1L, "first", CREATED_1, UPDATED_1, DUE_1));

        TemporalItemEntity reloaded = reload(1L);
        assertThat(reloaded.createdAt())
                .as("Instant 必须回到同一个时刻：被 JVM 时区挪过就会差出整小时")
                .isEqualTo(CREATED_1);
        assertThat(reloaded.updatedAt()).as("LocalDateTime 是无时区的墙上时间，不得被换算").isEqualTo(UPDATED_1);
        assertThat(reloaded.dueDate()).as("LocalDate 落 date 列，不得带出时刻").isEqualTo(DUE_1);
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
    @DisplayName("查询：LocalDateTime 与 LocalDate 的比较各走各的列")
    void queryByLocalDateTimeAndLocalDateComparison() {
        seedThreeRows();

        assertThat(ids(query().where(TemporalItemEntity::updatedAt)
                        .ge(UPDATED_2)
                        .orderBy(TemporalItemEntity::id)
                        .asc()
                        .list()))
                .as("updated_at >= %s", UPDATED_2)
                .isEqualTo(expectedIds(
                        row -> row.updatedAt() != null && !row.updatedAt().isBefore(UPDATED_2)));

        assertThat(ids(query().where(TemporalItemEntity::dueDate)
                        .le(LocalDate.of(2024, 3, 31))
                        .orderBy(TemporalItemEntity::id)
                        .asc()
                        .list()))
                .as("due_date <= 2024-03-31")
                .isEqualTo(expectedIds(
                        row -> row.dueDate() != null && !row.dueDate().isAfter(LocalDate.of(2024, 3, 31))));

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
        assertThat(reloaded.createdAt()).isEqualTo(CREATED_2);
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
