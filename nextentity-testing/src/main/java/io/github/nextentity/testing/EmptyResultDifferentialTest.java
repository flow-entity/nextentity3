package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.BaseWhereStep;
import io.github.nextentity.api.Expression;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.model.Slice;
import io.github.nextentity.api.model.Tuple2;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/// 「一行都不匹配」时的差分 oracle：真库执行 vs 内存独立计算。
///
/// 为什么要专门为「空集」写一整个差分基类：空集是本模块里**手算常量最容易说谎**的一块，
/// 因为它的正确答案不是一个值，而是一张「0 / `null` / 空列表 / 一行」的四象限表：
/// ```text
/// 终止符                   零行时的正确结果        容易写错成的结果
/// list() / list(n)         []                     null / 抛异常
/// first() / single()       null                   抛异常 / 空 Optional
/// count()（终止符）          0L（long）              null
/// exists()                 false                  null
/// slice().total()          0L                     null
/// 聚合投影 list()          **恰好一行**            []  ← 最常错：把「无输入行」当成「无输出行」
///   count(id) 的值          0L                      null
///   sum/avg/min/max 的值     null                   0
/// groupBy 后的 list()      []                     **一行全 null** ← 其次常错
/// ```
/// 这张表里每一格都是「SQL 语义」与「直觉」分道扬镳的地方，而直觉写出来的常量会与实现**一起**错。
/// 所以这里不钉手算常量，而是：
///
/// - 真库一侧：`query()…` 走到某个终止符，拿真实返回；
/// - 内存一侧：**独立**地对 {@link ConsistencyTestBase#fixtureRows()} 求同一条谓词，得到「应当匹配的行」，
///   再由它派生期望值（空集 → `[]`、`0`、`false`、`null`）；
/// - 两边比对，并且每一格都先断言「内存这一侧确实是空集」——否则夹具一改，这一格会变成
///   「拿非空结果与 `[]` 比」的假红，或者更糟：变成什么都没在验证的假绿。
///
/// 内存一侧只回答「匹配了哪些行」（{@link #memoryRows}），**不**回答「零行时聚合投影返回几行」——
/// 后者是 SQL 的 `SELECT COUNT(x) FROM t WHERE false` 语义（一个聚合函数、无 `GROUP BY`，
/// 恒返回一行），由本类的元组断言钉住，并在注释里写明依据是 SQL-92 的 `GROUP BY` 子句语义。
///
/// **实测结论（H2 `MODE=MySQL` 与 MySQL 8.0.18 逐格一致）**：
/// ```text
/// 零行的实体查询      : list=[] list(10)=[] first=null single=null count=0L exists=false
///                       slice(0,10)=data[]+total 0L；slice(5,10) 同；orderBy+slice 同
///                       in(空集合) → 0 行（恒假，不是语法错误）
/// 零行的单列投影      : score.first()=null, list=[]
/// 零行的聚合投影      : count(id) → list [0L] / first 0L / single 0L   ← 一行！
///                       countDistinct(id) → 0L
///                       sum/avg/min/max → list [null] / first null / single null
/// 零行的分组查询      : list=[] first=null single=null；+having=[]；+orderBy=[]
///                       count()=0L（没有组）exists()=false slice(0,10)=data[]+total 0L
/// 被拒绝的终止符      : 无 groupBy 的聚合投影上的 count() / exists() / slice() →
///                       UnsupportedOperationException（消息含「未实现」）
/// ```
/// 这些值全部与 `Collector` / `SliceStep` / `Aggregates` 的 javadoc 契约一致，
/// 因此本轮**没有**发现疑似生产 bug。
///
/// @param <T> 后端实体类型
public abstract class EmptyResultDifferentialTest<T extends Item> extends ConsistencyTestBase<T> {

    /// 夹具里不存在的 `note` 值：`ITEM_ROWS` 的 `note` 只有 `n1` / `n2` 与 `null`。
    private static final String NO_SUCH_NOTE = "no-such-note";

    /// 一格：一个恒假条件 + 一个终止符；内存侧先独立算出「应当匹配的行」，再由它派生期望。
    ///
    /// 两个条件形态（数值越界、字符串不存在）分开列：它们走的是不同的渲染路径
    /// （`>` 与 `=`，且一个的列在夹具里含 `null`、一个不含），只钉一个会漏掉另一条路径。
    ///
    /// @return 每格一条动态用例
    @TestFactory
    Stream<DynamicTest> terminalsOnEmptyMatchMatchMemoryModel() {
        return emptyCases().stream().flatMap(emptyCase -> {
            List<FixtureRow> memoryRows = memoryRows(emptyCase.filter());
            List<Long> expectedIds = idsOf(memoryRows);
            long expectedTotal = memoryRows.size();
            String prefix = emptyCase.name() + " → ";
            return Stream.of(
                    DynamicTest.dynamicTest(prefix + "list()", () -> {
                        assertEmptyInMemory(memoryRows);
                        assertThat(ids(emptyCase.query().get().list())).containsExactlyElementsOf(expectedIds);
                    }),
                    DynamicTest.dynamicTest(prefix + "list(10)", () -> {
                        assertEmptyInMemory(memoryRows);
                        assertThat(ids(emptyCase.query().get().list(10))).containsExactlyElementsOf(expectedIds);
                    }),
                    DynamicTest.dynamicTest(prefix + "first()", () -> {
                        assertEmptyInMemory(memoryRows);
                        assertThat(emptyCase.query().get().first()).as("空集没有首条").isNull();
                    }),
                    DynamicTest.dynamicTest(prefix + "single()", () -> {
                        assertEmptyInMemory(memoryRows);
                        assertThat(emptyCase.query().get().single())
                                .as("空集没有单一结果")
                                .isNull();
                    }),
                    DynamicTest.dynamicTest(prefix + "count()", () -> {
                        assertEmptyInMemory(memoryRows);
                        assertThat(emptyCase.query().get().count())
                                .as("终止符 count() 是 long")
                                .isEqualTo(expectedTotal);
                    }),
                    DynamicTest.dynamicTest(prefix + "exists()", () -> {
                        assertEmptyInMemory(memoryRows);
                        assertThat(emptyCase.query().get().exists()).isEqualTo(!memoryRows.isEmpty());
                    }),
                    DynamicTest.dynamicTest(prefix + "slice(0,10)", () -> {
                        assertEmptyInMemory(memoryRows);
                        Slice<T> slice = emptyCase.query().get().slice(0, 10);
                        assertThat(slice.data()).isEmpty();
                        assertThat(slice.total()).as("total 与 count() 同口径").isEqualTo(expectedTotal);
                    }),
                    DynamicTest.dynamicTest(prefix + "slice(5,10)", () -> {
                        assertEmptyInMemory(memoryRows);
                        Slice<T> slice = emptyCase.query().get().slice(5, 10);
                        assertThat(slice.data()).isEmpty();
                        assertThat(slice.total()).isEqualTo(expectedTotal);
                    }));
        });
    }

    /// 聚合投影在零行输入时**仍然返回恰好一行**——本类最容易被写错的一格。
    ///
    /// 依据是 SQL：`SELECT COUNT(x) FROM t WHERE false` 没有 `GROUP BY`，整表是一个组，
    /// 所以结果集恒为一行；`COUNT` 给 `0`，其余聚合给 `NULL`。
    /// 实测返回类型：`count` / `countDistinct` → `Long`（`0L`），`sum` / `min` / `max` → 列类型
    /// `Integer`（`null`），`avg` → `Double`（`null`）。
    ///
    /// @return 每格一条动态用例
    @TestFactory
    Stream<DynamicTest> aggregatesOnEmptyMatchStillReturnOneRow() {
        return Stream.of(
                DynamicTest.dynamicTest("count(id) → [0L]（一行，Long）", () -> {
                    List<Long> rows = noRowAggregate(Path.of(idRef()).count()).list();
                    assertThat(rows).as("无 GROUP BY 的聚合投影恒返回一行").containsExactly(0L);
                    assertThat(noRowAggregate(Path.of(idRef()).count()).first())
                            .as("first() 取到的就是那一行")
                            .isEqualTo(0L);
                }),
                DynamicTest.dynamicTest("countDistinct(id) → [0L]", () -> {
                    List<Long> rows =
                            noRowAggregate(Path.of(idRef()).countDistinct()).list();
                    assertThat(rows).containsExactly(0L);
                }),
                DynamicTest.dynamicTest("sum(score) → [null]（不是 0）", () -> {
                    List<Integer> rows =
                            noRowAggregate(Path.of(scoreRef()).sum()).list();
                    assertThat(rows).hasSize(1);
                    assertThat(rows.get(0)).as("sum 在零行上是 NULL，不是 0").isNull();
                }),
                DynamicTest.dynamicTest("avg(score) → [null]（Double）", () -> {
                    List<Double> rows =
                            noRowAggregate(Path.of(scoreRef()).avg()).list();
                    assertThat(rows).hasSize(1);
                    assertThat(rows.get(0)).as("avg 在零行上是 NULL").isNull();
                }),
                DynamicTest.dynamicTest("min(score) → [null]", () -> {
                    List<Integer> rows =
                            noRowAggregate(Path.of(scoreRef()).min()).list();
                    assertThat(rows).hasSize(1);
                    assertThat(rows.get(0)).isNull();
                }),
                DynamicTest.dynamicTest("max(score) → [null]", () -> {
                    List<Integer> rows =
                            noRowAggregate(Path.of(scoreRef()).max()).list();
                    assertThat(rows).hasSize(1);
                    assertThat(rows.get(0)).isNull();
                }));
    }

    /// 零行时其余「结果形态」的差分：分组（无组可出）、去重投影、分页、`IN 空集合`。
    ///
    /// 分组那一格是仅次于聚合的第二常错点：零输入行时**不应**出现「一行全 `null` 的组」，
    /// 而 `GROUP BY` 恰恰会把 `NULL` 归成一组，两件事很容易混为一谈——夹具里的 `NULL` 组
    /// 来自**有行**的情形（`HavingDifferentialTest` 钉那一侧），零行时没有任何行，也就没有组。
    ///
    /// @return 每格一条动态用例
    @TestFactory
    Stream<DynamicTest> groupedDistinctAndSliceOnEmptyMatchMatchMemoryModel() {
        return Stream.of(
                DynamicTest.dynamicTest("groupBy(note) → []（零行没有组，不是一行全 null）", () -> {
                    List<Tuple2<String, Long>> rows = query().select(
                                    Path.of(noteRef()), Path.of(idRef()).count())
                            .where(scoreRef())
                            .gt(1000)
                            .groupBy(noteRef())
                            .list();
                    assertThat(rows).isEmpty();
                }),
                DynamicTest.dynamicTest("groupBy(note) + having count(id) > 0 → []", () -> {
                    List<Tuple2<String, Long>> rows = query().select(
                                    Path.of(noteRef()), Path.of(idRef()).count())
                            .where(scoreRef())
                            .gt(1000)
                            .groupBy(noteRef())
                            .having(Path.of(idRef()).count().gt(0L))
                            .list();
                    assertThat(rows).isEmpty();
                }),
                DynamicTest.dynamicTest("groupBy(note) + orderBy(note) → []", () -> {
                    List<Tuple2<String, Long>> rows = query().select(
                                    Path.of(noteRef()), Path.of(idRef()).count())
                            .where(scoreRef())
                            .gt(1000)
                            .groupBy(noteRef())
                            .orderBy(Path.of(noteRef()))
                            .asc()
                            .list();
                    assertThat(rows).isEmpty();
                    assertThat(query().select(
                                            Path.of(noteRef()), Path.of(idRef()).count())
                                    .where(scoreRef())
                                    .gt(1000)
                                    .groupBy(noteRef())
                                    .first())
                            .isNull();
                }),
                DynamicTest.dynamicTest("distinct 单列投影 → list [] / count 0L", () -> {
                    assertThat(query().distinct()
                                    .select(Path.of(noteRef()))
                                    .where(scoreRef())
                                    .gt(1000)
                                    .list())
                            .isEmpty();
                    assertThat(query().distinct()
                                    .select(Path.of(scoreRef()))
                                    .where(scoreRef())
                                    .gt(1000)
                                    .count())
                            .as("COUNT(DISTINCT) 也不计 NULL，但零行时两者都是 0")
                            .isZero();
                }),
                DynamicTest.dynamicTest("单列投影（非聚合）→ first null / list []", () -> {
                    assertThat(query().select(Path.of(noteRef()))
                                    .where(scoreRef())
                                    .gt(1000)
                                    .first())
                            .isNull();
                    assertThat(query().select(Path.of(noteRef()))
                                    .where(scoreRef())
                                    .gt(1000)
                                    .list())
                            .isEmpty();
                }),
                DynamicTest.dynamicTest("in(空集合) → 恒假，0 行（不是语法错误）", () -> {
                    assertThat(query().where(scoreRef()).in(List.<Integer>of()).list())
                            .isEmpty();
                    assertThat(query().where(scoreRef()).in(List.<Integer>of()).count())
                            .isZero();
                }));
    }

    /// 分组规格上的 `count()` / `exists()` / `slice()` 按「组」统计：空表没有组，因此
    /// `count()` = 0、`exists()` = false、`slice()` = 空页（`total` = 0）；这三格是空结果语义的直接推论。
    ///
    /// 仍然被拒绝的是**无 `groupBy` 的聚合投影**上的三个终止符：`count()` 只渲染 `where` 会把
    /// 「一行聚合结果」当行数（静默错数），`exists()` 同理（结论反了），所以那三格保持拒绝。
    ///
    /// @return 每格一条动态用例
    @TestFactory
    Stream<DynamicTest> unsupportedTerminalsAreRejected() {
        return Stream.of(
                DynamicTest.dynamicTest("聚合投影 + count() → 拒绝", () -> assertThatThrownBy(
                                () -> noRowAggregate(Path.of(idRef()).count()).count())
                        .isInstanceOf(UnsupportedOperationException.class)
                        .hasMessageContaining("未实现")),
                DynamicTest.dynamicTest("聚合投影 + exists() → 拒绝", () -> assertThatThrownBy(
                                () -> noRowAggregate(Path.of(idRef()).count()).exists())
                        .isInstanceOf(UnsupportedOperationException.class)
                        .hasMessageContaining("未实现")),
                DynamicTest.dynamicTest("聚合投影 + slice() → 拒绝（总数依赖 count()）", () -> assertThatThrownBy(
                                () -> noRowAggregate(Path.of(idRef()).count()).slice(0, 10))
                        .isInstanceOf(UnsupportedOperationException.class)
                        .hasMessageContaining("未实现")),
                DynamicTest.dynamicTest(
                        "groupBy + count() → 0（没有组）", () -> assertThat(query().select(Path.of(noteRef()))
                                        .where(scoreRef())
                                        .gt(1000)
                                        .groupBy(noteRef())
                                        .count())
                                .as("空表没有任何分组键值，组数恒为 0")
                                .isZero()),
                DynamicTest.dynamicTest(
                        "groupBy + exists() → false（没有任何组）", () -> assertThat(query().select(Path.of(noteRef()))
                                        .where(scoreRef())
                                        .gt(1000)
                                        .groupBy(noteRef())
                                        .exists())
                                .as("空表没有任何组，存在性恒为 false")
                                .isFalse()),
                DynamicTest.dynamicTest("groupBy + slice() → 空页（total=0）", () -> {
                    Slice<String> slice = query().select(Path.of(noteRef()))
                            .where(scoreRef())
                            .gt(1000)
                            .groupBy(noteRef())
                            .slice(0, 10);
                    assertThat(slice.data()).as("空表没有组，页内数据为空").isEmpty();
                    assertThat(slice.total()).as("total 与组数同口径").isZero();
                }));
    }

    /// 一个恒假条件的两种写法。`filter` 是它在内存里的独立实现，`query` 是它在查询 API 里的写法；
    /// 两者不共享任何代码，因此「渲染错了」会在比对时暴露。
    ///
    /// @param <Q> 查询所处的步骤类型
    private record EmptyCase<Q>(String name, Predicate<FixtureRow> filter, Supplier<Q> query) {}

    /// @return 两个恒假条件：数值越界、字符串不存在
    private List<EmptyCase<BaseWhereStep<T, T>>> emptyCases() {
        return List.of(
                new EmptyCase<>(
                        "where score > 1000",
                        row -> row.score() != null && row.score() > 1000,
                        () -> query().where(scoreRef()).gt(1000)),
                new EmptyCase<>(
                        "where note = 'no-such-note'",
                        row -> NO_SUCH_NOTE.equals(row.note()),
                        () -> query().where(noteRef()).eq(NO_SUCH_NOTE)));
    }

    /// 内存一侧：对夹具独立求同一条谓词。注意 `score` 列含 `null`，谓词必须显式处理三值逻辑，
    /// 否则 `row.score() > 1000` 会抛 `NullPointerException`。
    ///
    /// @param filter 行级条件
    /// @return 该条件在夹具上匹配到的行
    private List<FixtureRow> memoryRows(Predicate<FixtureRow> filter) {
        return fixtureRows().stream().filter(filter).toList();
    }

    /// 后续所有格复用同一个恒假条件（`score > 1000`），保证「零行」这个前提只在一处定义。
    ///
    /// 注意顺序：`select` 在 `where` 之前（投影入口在查询入口上，`BaseWhereStep` 只有终止符、
    /// 条件与排序），因此本方法把「投影 + 恒假条件」封成一个可复用的步骤。
    ///
    /// @param expression 投影表达式（聚合或普通列）
    /// @param <R>        投影结果类型
    /// @return 一定不匹配任何夹具行的投影查询
    private <R> BaseWhereStep<T, R> noRowAggregate(Expression<T, R> expression) {
        return query().select(expression).where(scoreRef()).gt(1000);
    }

    /// 差分的前提：内存这一侧必须真的是空集。
    ///
    /// 夹具一改（例如有人加了一行 `score = 2000`），这一格立刻红，并直接指出「差分失效」，
    /// 而不是转成一个含义模糊的值比对失败。
    ///
    /// @param memoryRows 内存侧算出的匹配行
    private static void assertEmptyInMemory(List<FixtureRow> memoryRows) {
        assertThat(memoryRows).as("内存 oracle：这一格必须真的是空集，否则差分无意义").isEmpty();
    }

    /// @param items 后端返回的实体行
    /// @return 其主键序列（比对键用主键，避免实体字段差异干扰）
    private static List<Long> ids(List<? extends Item> items) {
        return items.stream().map(Item::id).toList();
    }

    /// @param rows 内存侧算出的夹具行
    /// @return 其主键序列
    private static List<Long> idsOf(List<FixtureRow> rows) {
        return rows.stream().map(FixtureRow::id).toList();
    }
}
