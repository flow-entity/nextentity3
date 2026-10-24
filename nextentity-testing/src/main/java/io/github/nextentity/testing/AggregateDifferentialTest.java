package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.api.model.Tuple;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/// 聚合家族的**差分** oracle：`count` / `sum` / `avg` / `min` / `max` 在三种分组
/// （按 `note`、按 `status`、按 `note` + `status`）上各算一遍，期望值由内存实现按 SQL 语义
/// 独立算出，**逐组逐值**比对——不是只比组数，也不是只比某几组。
///
/// 为什么逐值：组数对得上、值全错，是聚合最容易出的错（`sum` 写成 `count`、`avg` 除以行数
/// 而不是除以非空个数、`min`/`max` 写反、把 `null` 当 0 参与求和）。这些错在「只比组数」下
/// 全部通过，逐值比对时立刻露出来。
///
/// 差分分工（两条路径不相交）：
/// - 真库一侧：`select(分组键…, 聚合表达式).groupBy(键…)`，渲染走 jpa 的聚合分支；
/// - 内存一侧：{@link Memory#expected} 直接读 {@link Fixtures#ITEM_ROWS} 的原始值自己算，
///   不经过 nextentity 的任何生产代码，也不读后端结果。
///
/// **内存一侧按 SQL 语义写，不按「方便」写**（这几条正是聚合的坑）：
/// - `count(*)` 数**行**（本基类用 `count(id)` 表达：`id` 是主键、永不为 `null`，因此等于行数）；
/// - `count(列)` **不数 `null`**，因此 `count(score)` 比 `count(id)` 少一行（id=4 的 `score` 是 `null`）；
/// - `sum` / `avg` / `min` / `max` **忽略 `null`**：`null` 不参与，也不被当成 0；
/// - `avg` 的分母是**非空个数**，不是行数——夹具里 `note=null` 组的 `score` 是 `{10, null}`，
///   正确结果是 `10.0`；若实现按行数除会得到 `5.0`，这一格就是为切开它而设的；
/// - 整列全为 `null` 的组：`sum` / `avg` / `min` / `max` 都是 `null`（不是 `0`），
///   `count(列)` 是 `0`——夹具里 `note=n2` 组的 `score2` 整列为空，钉住这条语义；
/// - 无行时同理：`count` 是 `0`，其余是 `null`（见 {@link EmptyAggregateConsistencyTest}）。
///
/// **返回类型（实测，不是照文档抄）**：H2 `MODE=MySQL` 与 MySQL 8.0.18 逐格一致。
/// ```text
/// count(id) / count(score)  → Long     2 / 1
/// sum(score)                → Integer  10        （列是 Integer；API 的 sum() 返回 AggregateValue<T,U>，U 即列类型）
/// avg(score)                → Double   10.0      （AVG 固定 Double，见 NumberPath#avg）
/// min(score) / max(score)   → Integer  10 / 10
/// 全 NULL 组的 sum/avg/min/max → null（Java 侧就是 null，不是 0、不是 Double.NaN）
/// ```
/// 三个会踩的坑，写在这里省一次调试（都是实测，不是照文档抄）：
/// - `sum()` 的静态类型跟着列走（`Integer` 列 → `Integer`），**不是** `Long`/`BigDecimal`；
///   方言的 `SUM(int)` 在 SQL 层是 `BIGINT`，但结果被收回成 `Integer`。
///   溢出时**不会**静默回绕，而是抛 `org.hibernate.exception.DataException`（实测两行各 20 亿）：
///   ```text
///   H2   : Numeric value out of range: "4000000001" [22003-232]
///   MySQL: NumberOutOfRange: Value '4,000,000,001' is outside of valid range for type java.lang.Integer
///   ```
///   这条**故意不写成断言**：异常类是 JPA/Hibernate 层类型，出现在后端中立基类里就违约
///   （`nextentity-arch` 的 `TESTING_STAYS_BACKEND_FREE`）；而只断言「抛了某个 `RuntimeException`」
///   近乎同义反复。要钉它得在后端特定用例里做。
/// - `avg()` 的精度**是方言相关的**，所以本矩阵只放「均值可精确表示」的组：
///   ```text
///   同一份数据 score=1,1,2：H2(MODE=MySQL) → Double 1.3333333333333333
///                            MySQL 8.0.18   → Double 1.3333     （AVG 在 MySQL 侧是 DECIMAL，位数不同）
///   ```
///   夹具里唯一带小数的均值是 `score2` 的 `NEW` 组（`{10, 5}` → `7.5`），两种方言都精确可表示，
///   所以逐值比对在两套装置上都成立。**不要**往这个基类里加不可整除的均值——那会变成方言差异。
/// - `BigDecimal` 列整条路径不在本矩阵范围内（夹具没有 `BigDecimal` 列），因此这里对它的
///   返回类型与精度**不做任何断言**，也不做无证据的描述。
///
/// @param <T> 后端实体类型
public abstract class AggregateDifferentialTest<T extends Item> extends ConsistencyTestBase<T> {

    /// 每格一条动态用例：分组方式 × 聚合列 × 聚合函数。
    ///
    /// 用 `@TestFactory` 而不是 `@ParameterizedTest`：`junit-jupiter-params` 不在
    /// `nextentity-testing` 的编译类路径上（本模块只声明 `junit-jupiter-api`），改 pom 是红线。
    ///
    /// 格数 = 3 种分组 × （1 格 `count(id)` + 2 列 × 5 个聚合函数）= 33 格。
    /// `count(id)` 与列无关（`id` 永不为 `null`），因此每种族只生成一次。
    ///
    /// @return 每格一条动态用例
    @TestFactory
    Stream<DynamicTest> everyGroupingAndMeasureMatchesMemoryOracle() {
        return Stream.of(Grouping.values())
                .flatMap(grouping -> Stream.concat(
                        Stream.of(Measure.COUNT_OF_ID).map(measure -> cell(grouping, Column.SCORE, measure)),
                        Stream.of(Column.values()).flatMap(column -> Stream.of(Measure.values())
                                .filter(measure -> measure != Measure.COUNT_OF_ID)
                                .map(measure -> cell(grouping, column, measure)))));
    }

    private DynamicTest cell(Grouping grouping, Column column, Measure measure) {
        String name = grouping + "/" + (measure == Measure.COUNT_OF_ID ? "" : column + "/") + measure;
        return DynamicTest.dynamicTest(name, () -> assertCell(grouping, column, measure));
    }

    private void assertCell(Grouping grouping, Column column, Measure measure) {
        Map<Object, Object> expected = Memory.expected(grouping, column, measure);
        Map<Object, Object> actual = actualBy(grouping, column, measure);

        assertThat(expected).as("内存 oracle 必须至少算出一组").isNotEmpty();
        assertThat(actual.keySet())
                .as("分组键集合；内存 oracle 期望 %s", expected.keySet())
                .containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach((key, value) ->
                assertThat(actual.get(key)).as("组 %s 上的聚合值", key).isEqualTo(value));
    }

    private Map<Object, Object> actualBy(Grouping grouping, Column column, Measure measure) {
        return switch (grouping) {
            case BY_NOTE -> byNote(column, measure);
            case BY_STATUS -> byStatus(column, measure);
            case BY_NOTE_AND_STATUS -> byNoteAndStatus(column, measure);
        };
    }

    private Map<Object, Object> byNote(Column column, Measure measure) {
        PathRef.StringRef<T> key = noteRef();
        return switch (measure) {
            case COUNT_OF_ID ->
                index(
                        query().select(Path.of(key), Path.of(idRef()).count())
                                .groupBy(key)
                                .list(),
                        1);
            case COUNT_VALUES ->
                index(
                        query().select(Path.of(key), Path.of(columnRef(column)).count())
                                .groupBy(key)
                                .list(),
                        1);
            case SUM ->
                index(
                        query().select(Path.of(key), Path.of(columnRef(column)).sum())
                                .groupBy(key)
                                .list(),
                        1);
            case AVG ->
                index(
                        query().select(Path.of(key), Path.of(columnRef(column)).avg())
                                .groupBy(key)
                                .list(),
                        1);
            case MIN ->
                index(
                        query().select(Path.of(key), Path.of(columnRef(column)).min())
                                .groupBy(key)
                                .list(),
                        1);
            case MAX ->
                index(
                        query().select(Path.of(key), Path.of(columnRef(column)).max())
                                .groupBy(key)
                                .list(),
                        1);
        };
    }

    private Map<Object, Object> byStatus(Column column, Measure measure) {
        PathRef<T, ItemStatus> key = statusRef();
        return switch (measure) {
            case COUNT_OF_ID ->
                index(
                        query().select(Path.of(key), Path.of(idRef()).count())
                                .groupBy(key)
                                .list(),
                        1);
            case COUNT_VALUES ->
                index(
                        query().select(Path.of(key), Path.of(columnRef(column)).count())
                                .groupBy(key)
                                .list(),
                        1);
            case SUM ->
                index(
                        query().select(Path.of(key), Path.of(columnRef(column)).sum())
                                .groupBy(key)
                                .list(),
                        1);
            case AVG ->
                index(
                        query().select(Path.of(key), Path.of(columnRef(column)).avg())
                                .groupBy(key)
                                .list(),
                        1);
            case MIN ->
                index(
                        query().select(Path.of(key), Path.of(columnRef(column)).min())
                                .groupBy(key)
                                .list(),
                        1);
            case MAX ->
                index(
                        query().select(Path.of(key), Path.of(columnRef(column)).max())
                                .groupBy(key)
                                .list(),
                        1);
        };
    }

    private Map<Object, Object> byNoteAndStatus(Column column, Measure measure) {
        PathRef.StringRef<T> note = noteRef();
        PathRef<T, ItemStatus> status = statusRef();
        return switch (measure) {
            case COUNT_OF_ID ->
                index(
                        query().select(
                                        Path.of(note),
                                        Path.of(status),
                                        Path.of(idRef()).count())
                                .groupBy(note)
                                .groupBy(status)
                                .list(),
                        2);
            case COUNT_VALUES ->
                index(
                        query().select(
                                        Path.of(note),
                                        Path.of(status),
                                        Path.of(columnRef(column)).count())
                                .groupBy(note)
                                .groupBy(status)
                                .list(),
                        2);
            case SUM ->
                index(
                        query().select(
                                        Path.of(note),
                                        Path.of(status),
                                        Path.of(columnRef(column)).sum())
                                .groupBy(note)
                                .groupBy(status)
                                .list(),
                        2);
            case AVG ->
                index(
                        query().select(
                                        Path.of(note),
                                        Path.of(status),
                                        Path.of(columnRef(column)).avg())
                                .groupBy(note)
                                .groupBy(status)
                                .list(),
                        2);
            case MIN ->
                index(
                        query().select(
                                        Path.of(note),
                                        Path.of(status),
                                        Path.of(columnRef(column)).min())
                                .groupBy(note)
                                .groupBy(status)
                                .list(),
                        2);
            case MAX ->
                index(
                        query().select(
                                        Path.of(note),
                                        Path.of(status),
                                        Path.of(columnRef(column)).max())
                                .groupBy(note)
                                .groupBy(status)
                                .list(),
                        2);
        };
    }

    /// 把「分组键 + 聚合值」的行收成 map，并拒绝重复分组键。
    ///
    /// 用 [Tuple#get] 按位置取值，两种分组（1 键 / 2 键）共用同一段收表逻辑：
    /// 键 = 前 `keyColumns` 列（多列时包成 `List`），值 = 最后一列。
    /// 重复键直接判失败：`GROUP BY` 每组只能返回一行，多出来的一行是「分组没生效」的证据，
    /// 若静默覆盖就会把错答案读成正确答案。
    ///
    /// @param rows 真库返回的「键 + 值」行
    /// @param keyColumns 键列数
    /// @return 键 → 聚合值
    private static Map<Object, Object> index(List<? extends Tuple> rows, int keyColumns) {
        Map<Object, Object> indexed = new LinkedHashMap<>();
        for (Tuple row : rows) {
            Object key = groupKey(row, keyColumns);
            if (indexed.containsKey(key)) {
                throw new AssertionError("分组键重复：" + key + "；GROUP BY 每组只应返回一行");
            }
            indexed.put(key, row.get(row.size() - 1));
        }
        return indexed;
    }

    private static Object groupKey(Tuple row, int keyColumns) {
        return keyColumns == 1 ? row.get(0) : Arrays.asList(row.get(0), row.get(1));
    }

    private PathRef.NumberRef<T, Integer> columnRef(Column column) {
        return switch (column) {
            case SCORE -> scoreRef();
            case SCORE2 -> score2Ref();
        };
    }

    /// `count(*)` 与 `count(列)` 的差别：前者数行，后者跳过 `null`。
    ///
    /// 这是聚合里唯一「两个 count 语义不同」的地方，且差别只有一行，靠矩阵里的分组比对容易被
    /// 「组内行数恰好等于非空个数」掩盖（例如 `note=n1` 组两者都是 1），所以单钉一条：
    /// ```text
    /// query().count()                    → 4   （终止符，数行；实测 long）
    /// select(count(id)).first()          → 4   （id 非空，等价于行数）
    /// select(count(score)).first()       → 3   （id=4 的 score 是 null，被跳过）
    /// ```
    @Test
    void countStarCountsEveryRowWhileCountOfColumnSkipsNulls() {
        long rows = Fixtures.ITEM_ROWS.size();
        long nonNullScores = Fixtures.ITEM_ROWS.stream()
                .map(FixtureRow::score)
                .filter(Objects::nonNull)
                .count();

        assertThat(query().count()).as("count(*)：数行").isEqualTo(rows);
        assertThat(query().select(Path.of(idRef()).count()).first())
                .as("count(id)：id 非空，等于行数")
                .isEqualTo(rows);
        assertThat(query().select(Path.of(scoreRef()).count()).first())
                .as("count(score)：跳过 null 行")
                .isEqualTo(nonNullScores);
    }

    /// 整列全为 `null` 的组：`sum` / `avg` / `min` / `max` 都是 `null`，`count(列)` 是 `0`，
    /// 而 `count(id)` 仍是该组的行数。
    ///
    /// 夹具里 `score2` 只有 id=3 为 `null`，而 id=3 自成一组（`note=n2`、`status=DONE`），
    /// 于是那一组的 `score2` 整列为空。这是矩阵里唯一能钉住「全 NULL」语义的格子，
    /// 单独钉一条是因为「`null` 而不是 `0`」正是实现最容易含糊过去的地方（`sum` 返回 `0` 也「看着合理」）。
    /// 实测两套装置（H2 `MODE=MySQL` / MySQL 8.0.18）一致：
    /// ```text
    /// note=n2 组：count(id)=1  count(score2)=0  sum=null  avg=null  min=null  max=null
    /// ```
    @Test
    void nullOnlyGroupAggregatesToNull() {
        assertThat(actualBy(Grouping.BY_NOTE, Column.SCORE2, Measure.COUNT_OF_ID)
                        .get(NOTE_2))
                .as("count(id)：组内行数")
                .isEqualTo(1L);
        assertThat(actualBy(Grouping.BY_NOTE, Column.SCORE2, Measure.COUNT_VALUES)
                        .get(NOTE_2))
                .as("count(score2)：非空个数")
                .isEqualTo(0L);
        assertThat(actualBy(Grouping.BY_NOTE, Column.SCORE2, Measure.SUM).get(NOTE_2))
                .as("sum：全 NULL 组是 null，不是 0")
                .isNull();
        assertThat(actualBy(Grouping.BY_NOTE, Column.SCORE2, Measure.AVG).get(NOTE_2))
                .as("avg：全 NULL 组是 null")
                .isNull();
        assertThat(actualBy(Grouping.BY_NOTE, Column.SCORE2, Measure.MIN).get(NOTE_2))
                .as("min：全 NULL 组是 null")
                .isNull();
        assertThat(actualBy(Grouping.BY_NOTE, Column.SCORE2, Measure.MAX).get(NOTE_2))
                .as("max：全 NULL 组是 null")
                .isNull();
    }

    /// 分组方式：单列 `note`、单列 `status`、双列 `note` + `status`。
    ///
    /// 三种都要，因为「分组键的实现」与「聚合的实现」是两条代码路径：单列能过、双列接不上，
    /// 或双列把键拼错（两列的 `GROUP BY` 顺序、`null` 键的处理），只有双列那一格会红。
    enum Grouping {
        BY_NOTE,
        BY_STATUS,
        BY_NOTE_AND_STATUS;

        /// 内存一侧的分组键，必须与真库返回的键**逐类型相等**。
        ///
        /// 多列键用 `Arrays.asList(...)`（允许 `null` 元素，`List.of` 会抛 NPE），
        /// 与真库侧 `index(rows, 2)` 拼出来的键是同一形状，`equals` 才能对上。
        ///
        /// @param row 夹具行
        /// @return 该行所属组的键
        Object keyOf(FixtureRow row) {
            return switch (this) {
                case BY_NOTE -> row.note();
                case BY_STATUS -> row.status();
                case BY_NOTE_AND_STATUS -> Arrays.asList(row.note(), row.status());
            };
        }
    }

    /// 被聚合的列。两列都进矩阵：`score` 有 `null`（id=4），`score2` 有 `null`（id=3）
    /// 且 `note=n2` 组整列为空，合起来才把「部分为空」与「全为空」两种情形都覆盖到。
    enum Column {
        SCORE,
        SCORE2;

        /// 该列在组内的**非空**值，按夹具顺序。
        ///
        /// @param members 组内行
        /// @return 非空值列表；全空时为空列表
        List<Integer> valuesIn(List<FixtureRow> members) {
            List<Integer> values = new ArrayList<>();
            for (FixtureRow row : members) {
                Integer value =
                        switch (this) {
                            case SCORE -> row.score();
                            case SCORE2 -> row.score2();
                        };
                if (value != null) {
                    values.add(value);
                }
            }
            return values;
        }
    }

    /// 聚合函数，以及它们在内存里的独立实现（严格按 SQL 语义）。
    enum Measure {
        /// `count(id)`：`id` 永不为 `null`，因此等于组内行数（`count(*)` 的组内等价物）。
        COUNT_OF_ID,
        /// `count(列)`：只数非空值。
        COUNT_VALUES,
        /// `sum(列)`：忽略 `null`；全空时 `null`。
        SUM,
        /// `avg(列)`：忽略 `null`，分母是非空个数；全空时 `null`。
        AVG,
        /// `min(列)`：忽略 `null`；全空时 `null`。
        MIN,
        /// `max(列)`：忽略 `null`；全空时 `null`。
        MAX;

        /// 内存一侧算出的期望值。
        ///
        /// 返回类型刻意与实测的真库返回类型一致（`Long` / `Integer` / `Double`），
        /// 这样「实现返回了 `Long` 而契约是 `Integer`」这类类型漂移也会被逐值比对抓到。
        ///
        /// @param column 聚合列
        /// @param members 组内行
        /// @return 期望值；全空组的 `sum` / `avg` / `min` / `max` 为 `null`
        Object valueOf(Column column, List<FixtureRow> members) {
            if (this == COUNT_OF_ID || this == COUNT_VALUES) {
                return countOf(column, members);
            }
            return aggregate(column.valuesIn(members));
        }

        /// 两种 `count` 的差别只在这里：`*` 数行，列名数非空值。
        private long countOf(Column column, List<FixtureRow> members) {
            return this == COUNT_OF_ID
                    ? members.size()
                    : column.valuesIn(members).size();
        }

        /// 忽略 `null` 的四则聚合；**空集合返回 `null`**，与 SQL 一致（不是 `0`）。
        ///
        /// 这里刻意不返回 `Optional` 或哨兵值：断言里 `null` 本身就是主证据之一。
        private Object aggregate(List<Integer> values) {
            if (values.isEmpty()) {
                return null;
            }
            IntStream numbers = values.stream().mapToInt(Integer::intValue);
            return switch (this) {
                case SUM -> numbers.sum();
                case AVG -> numbers.average().orElseThrow();
                case MIN -> numbers.min().orElseThrow();
                case MAX -> numbers.max().orElseThrow();
                case COUNT_OF_ID, COUNT_VALUES -> throw new IllegalStateException("已在 valueOf 里返回");
            };
        }
    }

    /// 内存一侧：期望值只由 {@link Fixtures#ITEM_ROWS} 算出，不碰后端、不碰生产代码。
    private static final class Memory {

        private Memory() {}

        /// 按分组方式把夹具分组，再对每组算出该格期望值。
        ///
        /// 用 `LinkedHashMap` 手工分组而不是 `Collectors.groupingBy`：后者对 `null` 键直接抛
        /// `NullPointerException`，而 `note` 的 `null` 组正是本矩阵的关键格。
        ///
        /// @param grouping 分组方式
        /// @param column 聚合列
        /// @param measure 聚合函数
        /// @return 键 → 期望值
        static Map<Object, Object> expected(Grouping grouping, Column column, Measure measure) {
            Map<Object, List<FixtureRow>> groups = new LinkedHashMap<>();
            for (FixtureRow row : Fixtures.ITEM_ROWS) {
                groups.computeIfAbsent(grouping.keyOf(row), key -> new ArrayList<>())
                        .add(row);
            }
            Map<Object, Object> values = new LinkedHashMap<>();
            groups.forEach((key, members) -> values.put(key, measure.valueOf(column, members)));
            return values;
        }
    }
}
