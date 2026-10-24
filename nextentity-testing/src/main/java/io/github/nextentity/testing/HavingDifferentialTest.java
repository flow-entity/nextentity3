package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.api.model.Tuple2;
import io.github.nextentity.api.model.Tuple3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/// 分组后过滤（`having`）的**差分** oracle：真库执行 vs 内存独立计算，逐组逐值比对。
///
/// 为什么这一族需要差分而不是手算常量：`having` 的正确性取决于**三个阶段的先后**，而三阶段的
/// 顺序错误在「只看最后剩下哪些组」时经常看不出来：
/// ```text
/// WHERE（行级） → GROUP BY（分组） → HAVING（组级聚合条件）
/// ```
/// 把 `having` 的条件提到分组前、或把 `where` 的条件挪到分组后，结果**有时**仍然一样，
/// 于是手算常量会绿；差分矩阵里只要有一格的期望值是按正确顺序算出来的，写错的顺序就会红。
///
/// 差分分工（两条路径不相交）：
/// - 真库一侧：`select(键…, 聚合).where(行级条件).groupBy(键…).having(组级条件).orderBy(键).asc()`；
/// - 内存一侧：{@link Oracle#byNote} / {@link Oracle#byNoteAndStatus} 直接读
///   {@link ConsistencyTestBase#fixtureRows()}，自己过滤 → 自己分组 → 自己算聚合 → 自己按 `having` 过滤 →
///   自己排序，产出期望行；不经过 nextentity 的任何生产代码，也不回读后端结果。
///
/// 内存一侧的行数据一律取自 {@link ConsistencyTestBase#fixtureRows()}（`seed()` 灌进后端的正是同一份）：覆写它
/// 就同时换了「灌的」和「算的」，不会退化成「灌的是 A、算的是 B」的静默假绿；oracle 的独立性
/// 要求的是**求值逻辑**不调用生产代码，行数据本身是输入而非答案。
///
/// **内存一侧按 SQL 语义写，不按「方便」写**（这几条正是 `having` 的坑）：
/// - `where` **先**过滤行、`having` **后**过滤组：`where(score >= 20)` 把 `note=null` 组的两行
///   都干掉（id=1 是 10，id=4 是 `null` → `null >= 20` 是 `UNKNOWN`，也不留），因此那一组在
///   `having` 之前就不存在了；把它算成「先分组再过滤」会得到多一组的结果；
/// - `GROUP BY` 对 `NULL` 是**归成一组**（不是每组一行、也不是丢掉）：夹具里 id=1、4 的
///   `note` 都是 `null`，分组后是**一个** `null` 组、`count(id)=2`；
/// - 聚合忽略 `null`：`note=null` 组的 `score` 是 `{10, null}` → `sum=10`、`avg=10.0`
///   （分母是非空个数 1，不是行数 2）；
/// - **`having` 比较的 `null` 是 `UNKNOWN`，该组被排除**（不是「留下」、也不是报错）：
///   `note=n2` 组的 `score2` 整列为空 → `sum(score2)` 是 `null` → `having sum(score2) > 0`
///   把这一组**排除**。这一格单独钉，因为把 `null` 当 0 的实现在这里会多留一组；
/// - `count(列)` **不数 `null`**：`note=null` 组 `count(id)=2` 而 `count(score)=1`，
///   因此 `having count(score) > 1` 一个组都不留，而 `having count(id) > 1` 留下 `null` 组；
/// - `having` 不带 `groupBy` 时过滤的是**全表聚合**（SQL 允许），此时整表是一个组：
///   `having count(id) > 3` 留 1 行 `4`，`having count(id) > 4` 是空列表。
///
/// **实测结论（H2 `MODE=MySQL` 与 MySQL 8.0.18 逐格一致，见下表）**，本矩阵里的阈值都取在
/// 「两种方言能精确表示」的位置上，`avg` 那一格刻意只用 `{10}`、`{20}`、`{30}` 这类可精确表示的组——
/// 不可整除的均值是**方言差异**（`avg` 精度由方言决定，见 {@link AggregateDifferentialTest} 的说明），
/// 放进后端中立基类会变成假红：
/// ```text
/// 分组（note）         : [null|2, n1|1, n2|1]          ← NULL 归成一组
/// having count(id) > 1 : [null|2]
/// having count(id) >= 2: [null|2]
/// having count(id) >= 1: [null|2, n1|1, n2|1]         ← 一个组都不排除
/// having sum(score) > 20   : [n2|30]
/// having avg(score) > 15.0 : [n1|20.0, n2|30.0]        （Double）
/// having min(score) >= 20  : [n1|20, n2|30]            （Integer）
/// having max(score) < 20   : [null|10]
/// having sum(score2) > 0   : [null|15, n1|10]          ← n2 组被 NULL → UNKNOWN 排除
/// having count(score) > 1  : []                        ← count(列) 不数 NULL
/// where(score>=20)+having count>=1 : [n1|1, n2|1]      ← NULL 组在 where 阶段已消失
/// where(score>=20)+having count>=2 : []
/// 多列分组(note,status)+having count>=1 : [null|NEW|2, n1|ACTIVE|1, n2|DONE|1]
/// 无 groupBy having count > 3 : [4]；> 4 : []
/// ```
/// 返回类型（实测）：`count(id)` / `count(score)` → `Long`；`sum` / `min` / `max` → 列类型
/// （夹具是 `Integer`）；`avg` → `Double`。`having` 的阈值必须与这些类型**逐类型**匹配
/// （`count(id).gt(1L)` 传 `long`、`avg(score).gt(15.0)` 传 `double`），否则是编译期错误——
/// 这是 API 的类型系统在帮忙，不是运行期容错。
///
/// @param <T> 后端实体类型
public abstract class HavingDifferentialTest<T extends Item> extends ConsistencyTestBase<T> {

    /// 全部格：`count` 条件 4 格 + `sum`/`avg`/`min`/`max` 条件 4 格 + `NULL` 组 3 格 +
    /// `where` 与 `having` 组合、多列分组、无分组 5 格 = 16 格。
    ///
    /// 用 `@TestFactory` 而不是 `@ParameterizedTest`：`junit-jupiter-params` 不在
    /// `nextentity-testing` 的编译类路径上（本模块只声明 `junit-jupiter-api`），改 pom 是红线。
    ///
    /// @return 每格一条动态用例
    @TestFactory
    Stream<DynamicTest> everyHavingMatchesMemoryOracle() {
        return Stream.concat(
                Stream.concat(countCells(), valueCells()), Stream.concat(nullGroupCells(), compositionCells()));
    }

    /// `having` 作用在 `count` 上：`>` / `>=` 两个算子、全留、以及多次 `having` 的 `AND` 累加。
    private Stream<DynamicTest> countCells() {
        return Stream.of(
                cell(
                        "having count(id) > 1",
                        () -> query().select(
                                        Path.of(noteRef()), Path.of(idRef()).count())
                                .groupBy(noteRef())
                                .having(Path.of(idRef()).count().gt(1L))
                                .orderBy(Path.of(noteRef()))
                                .asc()
                                .list(),
                        () -> Oracle.byNote(
                                fixtureRows(), anyRow(), Oracle::countOf, members -> Oracle.countOf(members) > 1)),
                cell(
                        "having count(id) >= 2",
                        () -> query().select(
                                        Path.of(noteRef()), Path.of(idRef()).count())
                                .groupBy(noteRef())
                                .having(Path.of(idRef()).count().ge(2L))
                                .orderBy(Path.of(noteRef()))
                                .asc()
                                .list(),
                        () -> Oracle.byNote(
                                fixtureRows(), anyRow(), Oracle::countOf, members -> Oracle.countOf(members) >= 2)),
                cell(
                        "having count(id) >= 1（一个组都不排除）",
                        () -> query().select(
                                        Path.of(noteRef()), Path.of(idRef()).count())
                                .groupBy(noteRef())
                                .having(Path.of(idRef()).count().ge(1L))
                                .orderBy(Path.of(noteRef()))
                                .asc()
                                .list(),
                        () -> Oracle.byNote(
                                fixtureRows(), anyRow(), Oracle::countOf, members -> Oracle.countOf(members) >= 1)),
                cell(
                        "两次 having 按 AND 累加（count>1 且 sum>20 → 空集）",
                        () -> query().select(
                                        Path.of(noteRef()), Path.of(idRef()).count())
                                .groupBy(noteRef())
                                .having(Path.of(idRef()).count().gt(1L))
                                .having(Path.of(scoreRef()).sum().gt(20))
                                .list(),
                        () -> Oracle.byNote(
                                fixtureRows(),
                                anyRow(),
                                Oracle::countOf,
                                members -> Oracle.countOf(members) > 1
                                        && greaterThan(Oracle.sumOf(members, FixtureRow::score), 20))));
    }

    /// `having` 作用在 `sum` / `avg` / `min` / `max` 上：四类聚合各一格，含「一个组都不留」。
    private Stream<DynamicTest> valueCells() {
        return Stream.of(
                cell(
                        "having sum(score) > 20",
                        () -> query().select(
                                        Path.of(noteRef()), Path.of(scoreRef()).sum())
                                .groupBy(noteRef())
                                .having(Path.of(scoreRef()).sum().gt(20))
                                .orderBy(Path.of(noteRef()))
                                .asc()
                                .list(),
                        () -> Oracle.byNote(
                                fixtureRows(),
                                anyRow(),
                                members -> Oracle.sumOf(members, FixtureRow::score),
                                members -> greaterThan(Oracle.sumOf(members, FixtureRow::score), 20))),
                cell(
                        "having avg(score) > 15.0",
                        () -> query().select(
                                        Path.of(noteRef()), Path.of(scoreRef()).avg())
                                .groupBy(noteRef())
                                .having(Path.of(scoreRef()).avg().gt(15.0))
                                .orderBy(Path.of(noteRef()))
                                .asc()
                                .list(),
                        () -> Oracle.byNote(
                                fixtureRows(),
                                anyRow(),
                                members -> Oracle.avgOf(members, FixtureRow::score),
                                members -> greaterThan(Oracle.avgOf(members, FixtureRow::score), 15))),
                cell(
                        "having min(score) >= 20",
                        () -> query().select(
                                        Path.of(noteRef()), Path.of(scoreRef()).min())
                                .groupBy(noteRef())
                                .having(Path.of(scoreRef()).min().ge(20))
                                .orderBy(Path.of(noteRef()))
                                .asc()
                                .list(),
                        () -> Oracle.byNote(
                                fixtureRows(),
                                anyRow(),
                                members -> Oracle.minOf(members, FixtureRow::score),
                                members -> atLeast(Oracle.minOf(members, FixtureRow::score), 20))),
                cell(
                        "having max(score) < 20",
                        () -> query().select(
                                        Path.of(noteRef()), Path.of(scoreRef()).max())
                                .groupBy(noteRef())
                                .having(Path.of(scoreRef()).max().lt(20))
                                .orderBy(Path.of(noteRef()))
                                .asc()
                                .list(),
                        () -> Oracle.byNote(
                                fixtureRows(),
                                anyRow(),
                                members -> Oracle.maxOf(members, FixtureRow::score),
                                members -> lessThan(Oracle.maxOf(members, FixtureRow::score), 20))));
    }

    /// `NULL` 组的三格：分组把 `NULL` 归成一组、`having` 对 `null` 聚合的比较是 `UNKNOWN`、
    /// `count(列)` 与 `count(*)` 在 `having` 里同样分家。
    private Stream<DynamicTest> nullGroupCells() {
        return Stream.of(
                cell(
                        "groupBy(note) 把 NULL 归成一组（count(id)=2）",
                        () -> query().select(
                                        Path.of(noteRef()), Path.of(idRef()).count())
                                .groupBy(noteRef())
                                .orderBy(Path.of(noteRef()))
                                .asc()
                                .list(),
                        () -> Oracle.byNote(fixtureRows(), anyRow(), Oracle::countOf, members -> true)),
                cell(
                        "having sum(score2) > 0（n2 组聚合为 NULL → UNKNOWN → 排除）",
                        () -> query().select(
                                        Path.of(noteRef()), Path.of(score2Ref()).sum())
                                .groupBy(noteRef())
                                .having(Path.of(score2Ref()).sum().gt(0))
                                .orderBy(Path.of(noteRef()))
                                .asc()
                                .list(),
                        () -> Oracle.byNote(
                                fixtureRows(),
                                anyRow(),
                                members -> Oracle.sumOf(members, FixtureRow::score2),
                                members -> greaterThan(Oracle.sumOf(members, FixtureRow::score2), 0))),
                cell(
                        "having count(score) > 1（count(列) 不数 NULL → 一个组都不留）",
                        () -> query().select(
                                        Path.of(noteRef()), Path.of(idRef()).count())
                                .groupBy(noteRef())
                                .having(Path.of(scoreRef()).count().gt(1L))
                                .list(),
                        () -> Oracle.byNote(
                                fixtureRows(),
                                anyRow(),
                                Oracle::countOf,
                                members -> Oracle.countOf(members) > 1
                                        && Oracle.nonNullCountOf(members, FixtureRow::score) > 1)));
    }

    /// 阶段顺序与形态：`where` + `having` 组合（最容易写错的一格）、多列分组、无 `groupBy` 的
    /// `having`（全表聚合）。
    private Stream<DynamicTest> compositionCells() {
        return Stream.of(
                cell(
                        "where(score>=20) + having count(id) >= 1",
                        () -> query().select(
                                        Path.of(noteRef()), Path.of(idRef()).count())
                                .where(scoreRef())
                                .ge(20)
                                .groupBy(noteRef())
                                .having(Path.of(idRef()).count().ge(1L))
                                .orderBy(Path.of(noteRef()))
                                .asc()
                                .list(),
                        () -> Oracle.byNote(
                                fixtureRows(),
                                scoredAtLeast20(),
                                Oracle::countOf,
                                members -> Oracle.countOf(members) >= 1)),
                cell(
                        "where(score>=20) + having count(id) >= 2（每组只剩 1 行 → 空集）",
                        () -> query().select(
                                        Path.of(noteRef()), Path.of(idRef()).count())
                                .where(scoreRef())
                                .ge(20)
                                .groupBy(noteRef())
                                .having(Path.of(idRef()).count().ge(2L))
                                .list(),
                        () -> Oracle.byNote(
                                fixtureRows(),
                                scoredAtLeast20(),
                                Oracle::countOf,
                                members -> Oracle.countOf(members) >= 2)),
                cell(
                        "多列分组 (note, status) + having count(id) >= 1",
                        () -> query().select(
                                        Path.of(noteRef()),
                                        Path.of(statusRef()),
                                        Path.of(idRef()).count())
                                .groupBy(noteRef())
                                .groupBy(statusRef())
                                .having(Path.of(idRef()).count().ge(1L))
                                .orderBy(Path.of(noteRef()))
                                .asc()
                                .list(),
                        () -> Oracle.byNoteAndStatus(
                                fixtureRows(), anyRow(), Oracle::countOf, members -> Oracle.countOf(members) >= 1)),
                cell(
                        "无 groupBy：having count(id) > 3（全表聚合留一行）",
                        () -> query().select(Path.of(idRef()).count())
                                .having(Path.of(idRef()).count().gt(3L))
                                .list(),
                        () -> Oracle.wholeTable(fixtureRows(), count -> count > 3)),
                cell(
                        "无 groupBy：having count(id) > 4（全表聚合被拒 → 空列表）",
                        () -> query().select(Path.of(idRef()).count())
                                .having(Path.of(idRef()).count().gt(4L))
                                .list(),
                        () -> Oracle.wholeTable(fixtureRows(), count -> count > 4)));
    }

    /// 一格：真库结果与内存结果**逐元素**比对（顺序也钉住，因此每格都显式 `orderBy`）。
    ///
    /// 不用 `containsExactlyInAnyOrder`：`having` 之后的行序由 `orderBy` 决定，
    /// 用集合相等会放过「排序丢了」这类回归，而分组结果的顺序恰恰是消费方会依赖的。
    ///
    /// @param name     用例名（动态用例的显示名，也是失败时的定位信息）
    /// @param actual   真库一侧：一条完整查询
    /// @param expected 内存一侧：同一语义的独立计算
    /// @param <V>      行类型（`Tuple2` / `Tuple3` / 裸聚合值）
    /// @return 一条动态用例
    private <V> DynamicTest cell(String name, Supplier<List<V>> actual, Supplier<List<V>> expected) {
        return DynamicTest.dynamicTest(name, () -> {
            List<V> memoryRows = expected.get();
            assertThat(actual.get()).as("%s；内存 oracle 期望 %s", name, memoryRows).containsExactlyElementsOf(memoryRows);
        });
    }

    /// 内存一侧的行级谓词：不做任何过滤（只用于 `having` 的单条件格）。
    private static Predicate<FixtureRow> anyRow() {
        return row -> true;
    }

    /// 内存一侧的行级谓词：`score >= 20`，**按三值逻辑**——`null` 参与比较是 `UNKNOWN`，
    /// 该行不留下（夹具 id=4 的 `score` 是 `null`，因此这一行在 `where` 阶段就消失）。
    private static Predicate<FixtureRow> scoredAtLeast20() {
        return row -> row.score() != null && row.score() >= 20;
    }

    /// 内存一侧的比较：`null` 参与比较是 `UNKNOWN` → 谓词为 `false`（该组不留下）。
    ///
    /// `having` 里被聚合列全为 `null` 的组（夹具 `note=n2` 的 `score2`）就走这条路径。
    private static boolean greaterThan(Integer value, int bound) {
        return value != null && value > bound;
    }

    private static boolean lessThan(Integer value, int bound) {
        return value != null && value < bound;
    }

    private static boolean atLeast(Integer value, int bound) {
        return value != null && value >= bound;
    }

    /// 内存一侧的比较：`avg` 的结果是 `Double`，同样遵守「`null` → `UNKNOWN`」。
    private static boolean greaterThan(Double value, double bound) {
        return value != null && value > bound;
    }

    /// 内存一侧：期望行只由 {@link ConsistencyTestBase#fixtureRows()} 算出，不碰后端、不碰生产代码。
    ///
    /// 分组用 `LinkedHashMap` 手工做而不是 `Collectors.groupingBy`：后者对 `null` 键直接抛
    /// `NullPointerException`，而 `note` 的 `null` 组正是本矩阵的关键格。
    ///
    /// 排序规则与真库侧的 `orderBy(note).asc()` 对齐：`note` 的 `null` 排在**最前**
    /// （实测 H2 `MODE=MySQL` 与 MySQL 8.0.18 一致；`SortDifferentialTest` 里另有专门一格钉这条）。
    private static final class Oracle {

        private Oracle() {}

        /// 按 `note` 分组：过滤 → 分组 → 聚合 → `having` → 排序。
        ///
        /// @param rows 夹具行（来自 {@link ConsistencyTestBase#fixtureRows()}，与灌进后端的是同一份）
        /// @param rowFilter 行级条件（等价于 `where`），在分组**之前**执行
        /// @param aggregate 聚合函数（等价于 `select` 里的聚合表达式）
        /// @param keep      组级条件（等价于 `having`），在分组**之后**执行，入参是该组全部行
        /// @param <V>       聚合结果类型
        /// @return 期望行，按 `note` 升序、`null` 在前
        static <V> List<Tuple2<String, V>> byNote(
                List<FixtureRow> rows,
                Predicate<FixtureRow> rowFilter,
                Function<List<FixtureRow>, V> aggregate,
                Predicate<List<FixtureRow>> keep) {
            List<Tuple2<String, V>> expected = new ArrayList<>();
            groupsByNote(rows, rowFilter).forEach((key, members) -> {
                if (keep.test(members)) {
                    expected.add(new Tuple2<>(key, aggregate.apply(members)));
                }
            });
            expected.sort(Comparator.comparing(Tuple2::get0, Comparator.nullsFirst(Comparator.naturalOrder())));
            return expected;
        }

        /// 按 `note` + `status` 分组，其余同上。
        ///
        /// @param rows 夹具行（来自 {@link ConsistencyTestBase#fixtureRows()}）
        /// @param rowFilter 行级条件
        /// @param aggregate 聚合函数
        /// @param keep      组级条件
        /// @param <V>       聚合结果类型
        /// @return 期望行，按 `note` 升序、`null` 在前
        static <V> List<Tuple3<String, ItemStatus, V>> byNoteAndStatus(
                List<FixtureRow> rows,
                Predicate<FixtureRow> rowFilter,
                Function<List<FixtureRow>, V> aggregate,
                Predicate<List<FixtureRow>> keep) {
            Map<List<Object>, List<FixtureRow>> groups = new LinkedHashMap<>();
            for (FixtureRow row : rows) {
                if (rowFilter.test(row)) {
                    groups.computeIfAbsent(Arrays.asList(row.note(), row.status()), key -> new ArrayList<>())
                            .add(row);
                }
            }
            List<Tuple3<String, ItemStatus, V>> expected = new ArrayList<>();
            groups.forEach((key, members) -> {
                if (keep.test(members)) {
                    expected.add(new Tuple3<>((String) key.get(0), (ItemStatus) key.get(1), aggregate.apply(members)));
                }
            });
            expected.sort(Comparator.comparing(Tuple3::get0, Comparator.nullsFirst(Comparator.naturalOrder())));
            return expected;
        }

        /// 无 `groupBy` 的全表聚合 + `having`：整表是一个组，留下时结果恰好一行。
        ///
        /// @param rows 夹具行（来自 {@link ConsistencyTestBase#fixtureRows()}）
        /// @param keep 组级条件
        /// @return 一行全表聚合值，或被 `having` 拒绝后的空列表
        static List<Long> wholeTable(List<FixtureRow> rows, Predicate<Long> keep) {
            long count = countOf(rows);
            return keep.test(count) ? List.of(count) : List.of();
        }

        /// 行级过滤后按 `note` 分组（`LinkedHashMap` 允许 `null` 键）。
        private static Map<String, List<FixtureRow>> groupsByNote(
                List<FixtureRow> rows, Predicate<FixtureRow> rowFilter) {
            Map<String, List<FixtureRow>> groups = new LinkedHashMap<>();
            for (FixtureRow row : rows) {
                if (rowFilter.test(row)) {
                    groups.computeIfAbsent(row.note(), key -> new ArrayList<>()).add(row);
                }
            }
            return groups;
        }

        /// `count(*)` 的组内等价物：数行。
        static long countOf(List<FixtureRow> members) {
            return members.size();
        }

        /// `count(列)`：只数非空值。
        static long nonNullCountOf(List<FixtureRow> members, Function<FixtureRow, Integer> column) {
            return members.stream().map(column).filter(value -> value != null).count();
        }

        /// `sum(列)`：忽略 `null`；全空时为 `null`（不是 `0`）。
        static Integer sumOf(List<FixtureRow> members, Function<FixtureRow, Integer> column) {
            return members.stream()
                    .map(column)
                    .filter(value -> value != null)
                    .reduce(Integer::sum)
                    .orElse(null);
        }

        /// `avg(列)`：忽略 `null`，分母是非空个数；全空时为 `null`。
        static Double avgOf(List<FixtureRow> members, Function<FixtureRow, Integer> column) {
            List<Integer> values =
                    members.stream().map(column).filter(value -> value != null).toList();
            return values.isEmpty()
                    ? null
                    : values.stream().mapToInt(Integer::intValue).average().orElseThrow();
        }

        /// `min(列)`：忽略 `null`；全空时为 `null`。
        static Integer minOf(List<FixtureRow> members, Function<FixtureRow, Integer> column) {
            return members.stream()
                    .map(column)
                    .filter(value -> value != null)
                    .min(Integer::compare)
                    .orElse(null);
        }

        /// `max(列)`：忽略 `null`；全空时为 `null`。
        static Integer maxOf(List<FixtureRow> members, Function<FixtureRow, Integer> column) {
            return members.stream()
                    .map(column)
                    .filter(value -> value != null)
                    .max(Integer::compare)
                    .orElse(null);
        }
    }
}
