package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.github.nextentity.api.Expression;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.ProjectionMapper;
import io.github.nextentity.api.ProjectionMappers;
import io.github.nextentity.api.model.Slice;
import io.github.nextentity.api.model.Tuple;
import io.github.nextentity.api.model.Tuple2;
import io.github.nextentity.api.model.Tuple3;
import io.github.nextentity.api.model.Tuple4;
import io.github.nextentity.api.model.Tuple6;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/// 投影的**差分** oracle：真库按调用方给的列序取列，内存按同一份夹具原始字段自己拼出期望值，逐元素比对。
///
/// 为什么不复用 `ProjectionConsistencyTest` 的手算常量：那里的期望值只有一种来源（人脑）。
/// 列序写反时「常量」与「期望」错在同一处，用例照样绿。这里两条路径不相交：
/// - 真库一侧：`select(...)` / `project(...)` 渲染成 SQL，由 Hibernate 装配成 `Tuple2` / `Tuple3` / 映射器结果；
/// - 内存一侧：[Oracle] 只读 {@link #fixtureRows()} 的字段，用 `new Tuple2<>(row.id(), row.name())`
///   这类构造拼期望值，不调 nextentity 的任何生产代码，也不回读后端结果——否则就是自己证明自己。
///
/// **内存侧的行数据一律取 {@link #fixtureRows()}，不写死 {@link Fixtures#ITEM_ROWS}**：播种走的是
/// `@BeforeEach seed()` → `support().seedData(fixtureRows())`，而 `fixtureRows()` 可被子类覆写；
/// 内存侧若钉死默认夹具，覆写之后就变成「灌的是 A、算的是 B」，差分静默假绿、永远不红。
/// 行数据是**输入**不是答案，从同一入口取不损害 oracle 的独立性——独立性来自
/// 「[Oracle] 不调 nextentity 的任何生产代码、也不回读后端结果」。
///
/// 唯一写死的是各处 **oracle 自检**里的具体主键（如 `where score >= 20` 期望 `[2, 3]`、
/// `where score is null` 期望 `[4]`）：它们钉的是**默认夹具**的形状，用来证明期望值不是退化的。
/// 子类覆写 `fixtureRows()` 时这些自检会故意变红，提醒把自检一起改成新夹具的形状。
///
/// 覆盖的语义（每条都能被某一类 bug 证伪）：
/// - 两列 / 三列的**列序**：实参传反，或装配时按下标取错列（`get1()` 取到第一列）；
/// - 单列投影保留 `null` 行：`null` 既不能被过滤掉，也不能被兜成默认值；
/// - 投影 + `where`：行取舍由内存按夹具字段独立判断（含 SQL 三值逻辑：`null` 不满足 `>=`）；
/// - 投影 + `orderBy` + `list(n)`：截断发生在排序之后，且不改变列序；
/// - 映射器家族（`ProjectionMappers.select(...)` 四列、自定义五列映射器）：**每个字段**都对位，
///   而不是只断言对象非空；
/// - 六列投影（`ProjectionMappers.select` 的 `Tuple6` 路径）：四列以内看不见的「后两列互换」
///   在这格暴露——`score`/`score2` 同为 `Integer`、`note`/`status` 类型不同但位置相邻，
///   错位后强转不一定报错，只有逐元素比对才看得出；
/// - `distinct()` 修饰投影（单列 / 两列 / 三列 / 映射器四条路径）：去重后的值域与顺序由内存
///   用 `LinkedHashSet` 独立算出，见 [DistinctOracle]；
/// - `distinct()` + `slice` / `count` / 聚合：`total` 与 `count` 的口径是 `COUNT(DISTINCT 列)`，
///   **不计 `NULL`**，而去重后的行里 `NULL` 算一组——两者在含 `NULL` 的列上必然差 1，
///   这一格专治「`total` 用未去重行数」；多列去重的 `count` / `slice` 是被拒绝的形态，单独钉住。
///
/// @param <T> 后端实体类型
@SuppressWarnings("PMD.CouplingBetweenObjects") // 差分套件天然要把整个投影 API 表面（Tuple2~4 / 映射器 / Slice / distinct）
// 收在一处逐列比对：按耦合度拆开就失去「一处看全列序对应关系」的唯一好处（同 api 的 ProjectionMappers）
public abstract class ProjectionDifferentialTest<T extends Item> extends ConsistencyTestBase<T> {

    /// 多列 `distinct` 的 `count()` 拒绝消息前缀。只断前缀与列数，不断实体类型名（那是后端特有的）。
    private static final String MULTI_COLUMN_DISTINCT_COUNT_REJECTED = "多列 distinct 规格的 count 未实现";

    /// 两列投影：`select(id, name)` 的列序即期望值的列序；实参换个位置必须换来不同的结果集。
    ///
    /// 典型 bug：装配时写成 `new Tuple2<>(cast(row.get(1)), cast(row.get(0)))`——两种列序单独看都「像对的」，
    /// 只有把两种列序**都**与内存期望逐元素比对时才会露头，所以这里两种都跑。
    @Test
    void twoColumnProjectionKeepsArgumentOrder() {
        List<Tuple2<Long, String>> idFirst = query().select(Path.of(idRef()), Path.of(nameRef()))
                .orderBy(idRef())
                .asc()
                .list();
        List<Tuple2<String, Long>> nameFirst = query().select(Path.of(nameRef()), Path.of(idRef()))
                .orderBy(idRef())
                .asc()
                .list();
        List<Tuple2<Long, String>> expectedIdFirst = Oracle.idThenName(fixtureRows());
        List<Tuple2<String, Long>> expectedNameFirst = Oracle.nameThenId(fixtureRows());

        // oracle 自检：期望值逐行可分辨，否则「列序被忽略」也可能凑过去。
        assertThat(expectedIdFirst).hasSize(fixtureRows().size()).doesNotHaveDuplicates();
        assertThat(idFirst).containsExactlyElementsOf(expectedIdFirst);
        assertThat(nameFirst).containsExactlyElementsOf(expectedNameFirst);
    }

    /// 三列投影：`Tuple3` 的三个位置各取对列，末行的 `score` 是 `null`（夹具 id=4）。
    ///
    /// 除了逐元素比 `get0`~`get2`，还钉住 `get(int)` 与具名访问器同源——按下标取值是映射器路径的底层实现，
    /// 两者错位时「具名访问器对、下标访问错」会在映射器家族里放大。
    @Test
    void threeColumnProjectionKeepsArgumentOrder() {
        List<Tuple3<Long, String, Integer>> rows = query().select(
                        Path.of(idRef()), Path.of(nameRef()), Path.of(scoreRef()))
                .orderBy(idRef())
                .asc()
                .list();
        List<Tuple3<Long, String, Integer>> expected = Oracle.idThenNameThenScore(fixtureRows());

        // oracle 自检：期望值里既有 null 也有非 null 的 score，`null` 行没被内存侧漏掉。
        assertThat(expected)
                .hasSize(fixtureRows().size())
                .extracting(Tuple3::get2)
                .containsNull();
        assertThat(rows).containsExactlyElementsOf(expected);
        assertThat(rows).allSatisfy(row -> assertThat(row.get(2)).isEqualTo(row.get2()));
    }

    /// 单列投影：`score` 为 `null` 的行必须出现在结果里且值为 `null`（夹具 id=4）。
    ///
    /// 典型 bug：把结果集装配成「非空值列表」、或用 `Objects.requireNonNull` 兜默认值——行数或少一行、
    /// 或值被改成 0，三种都只在逐元素比对时可见。
    @Test
    void singleColumnProjectionKeepsNullRows() {
        List<Integer> scores = query().select(scoreRef()).orderBy(idRef()).asc().list();
        List<Integer> expected = Oracle.scores(fixtureRows());

        // oracle 自检：`null` 行必须真的在期望值里，否则这条测不出「null 行被过滤」。
        assertThat(expected).hasSize(fixtureRows().size()).containsNull();
        assertThat(scores).containsExactlyElementsOf(expected);
    }

    /// 投影 + `where`：行的取舍由内存按夹具字段独立判断，`null` 不满足 `>=`（SQL 三值逻辑）。
    ///
    /// 典型 bug：`where` 条件只作用在 `data` 上而投影仍取整表（先投影后过滤），或 `null` 行被 `>=` 命中。
    @Test
    void filteredProjectionMatchesMemoryFilter() {
        List<Tuple2<Long, String>> rows = query().select(Path.of(idRef()), Path.of(nameRef()))
                .where(scoreRef())
                .ge(20)
                .orderBy(idRef())
                .asc()
                .list();
        List<Tuple2<Long, String>> expected = Oracle.idThenName(Oracle.withScoreAtLeast(fixtureRows(), 20));

        // oracle 自检：过滤必须真的去掉行（既非空也不是全表），否则「where 被忽略」照样绿。
        assertThat(expected.stream().map(Tuple2::get0).toList()).containsExactly(2L, 3L);
        assertThat(rows).containsExactlyElementsOf(expected);
    }

    /// 投影 + `where` 的空值分支：`score is null` 只留 id=4，且它的 `score` 列投影出来是 `null`。
    ///
    /// 与上一条互补：那条证明「值比较能筛行」，这条证明「空值比较能筛行，且被选中的行的空值不被兜底」。
    @Test
    void nullFilterProjectionKeepsNullColumnValue() {
        List<Tuple3<Long, String, Integer>> rows = query().select(
                        Path.of(idRef()), Path.of(nameRef()), Path.of(scoreRef()))
                .where(scoreRef())
                .isNull()
                .orderBy(idRef())
                .asc()
                .list();
        List<Tuple3<Long, String, Integer>> expected = Oracle.idThenNameThenScore(Oracle.withNullScore(fixtureRows()));

        // oracle 自检：默认夹具里 `score is null` 只有 id=4，且它的投影值必须是 null。
        assertThat(expected.stream().map(Tuple3::get0).toList()).containsExactly(4L);
        assertThat(expected).allSatisfy(row -> assertThat(row.get2()).isNull());
        assertThat(rows).containsExactlyElementsOf(expected);
    }

    /// 投影 + `orderBy` + `list(n)`：排序在截断之前，截断后列序不变。
    ///
    /// 排序键取 `name`（四行互不相同、无 `null`），因此顺序在后端与内存两侧都确定，
    /// 可以用 `containsExactlyElementsOf` 而不是只断行数。典型 bug：`LIMIT` 先于 `ORDER BY`、
    /// 或截断后列序被打乱（换成 `Set` 装结果）。
    @Test
    void projectionWithOrderAndLimitTakesMemoryPrefix() {
        List<Tuple2<String, Long>> rows = query().select(Path.of(nameRef()), Path.of(idRef()))
                .orderBy(nameRef())
                .asc()
                .list(2);
        List<Tuple2<String, Long>> expected = Oracle.first(Oracle.nameThenId(Oracle.sortedByName(fixtureRows())), 2);

        // oracle 自检：截断真的生效（期望比全表短），否则「limit 被忽略」也能过。
        assertThat(expected).hasSize(2).isNotEqualTo(Oracle.nameThenId(Oracle.sortedByName(fixtureRows())));
        assertThat(rows).containsExactlyElementsOf(expected);
    }

    /// 四列映射器（`ProjectionMappers.select(...)`）：列数加宽后每一列都要对位，末列是枚举。
    ///
    /// 这条钉的是「列序 → 元组位置」在四列宽度上仍然成立；三列以内的 `select(...)` 重载不会覆盖它。
    @Test
    void projectionMapperKeepsEveryColumn() {
        List<Tuple4<Long, String, Integer, ItemStatus>> rows = query().project(ProjectionMappers.select(
                        Path.of(idRef()), Path.of(nameRef()), Path.of(scoreRef()), Path.of(statusRef())))
                .orderBy(idRef())
                .asc()
                .list();
        List<Tuple4<Long, String, Integer, ItemStatus>> expected = Oracle.idThenNameThenScoreThenStatus(fixtureRows());

        assertThat(expected).hasSize(fixtureRows().size()).doesNotHaveDuplicates();
        assertThat(rows).containsExactlyElementsOf(expected);
    }

    /// 自定义映射器：五个字段逐个对位（含可为空的 `score` / `note`），不是「对象非空」了事。
    ///
    /// 典型 bug：`map` 里下标错位（`note` 取到 `score` 的位置）——强转会抛 CCE，但相邻同类型列
    /// （`name` / `note` 都是 `String`）错位时静默通过；`containsExactlyElementsOf` 才看得出。
    @Test
    void customMapperAssignsEveryField() {
        List<ItemView> rows =
                query().project(new ItemViewMapper()).orderBy(idRef()).asc().list();
        List<ItemView> expected = fixtureRows().stream().map(ItemView::of).toList();

        // oracle 自检：期望值覆盖空值与非空值两种形态，字段错位才会被看出。
        assertThat(expected).extracting(ItemView::score).containsNull();
        assertThat(expected).extracting(ItemView::note).containsNull();
        assertThat(rows).containsExactlyElementsOf(expected);
    }

    /// 六列投影（`ProjectionMappers.select` 的 `Tuple6` 路径）：列序必须与实参顺序逐位一致。
    ///
    /// 为什么非要到六列：四列以内「首列对、其余错位」常被首列断言掩盖；六列是唯一能把
    /// 「前四列正确、后两列互换」暴露出来的规模。`score`/`score2` 同为 `Integer` 且相邻、
    /// `note`/`status` 相邻，装配时按下标取错一格不会抛异常，只有逐元素比对才看得见。
    ///
    /// 两种形态共用同一份内存期望：`ProjectionMappers.select(...)` 与 [SixColumnMapper#expressions()]
    /// 的列序相同，所以映射结果必须逐字段相等——其中一条把列序写歪，两者立刻分叉。
    ///
    /// 为什么不用 `@ParameterizedTest`：`nextentity-testing` 的编译类路径上没有
    /// `junit-jupiter-params`，参数化只能走 `@TestFactory` + `DynamicTest`。
    @TestFactory
    Stream<DynamicTest> everySixColumnProjectionMatchesMemoryOracle() {
        return Stream.of(
                dynamicTest("六列元组 `ProjectionMappers.select` 逐列对位", () -> {
                    List<Tuple6<Long, String, Integer, Integer, String, ItemStatus>> expected =
                            Oracle.sixColumns(fixtureRows());
                    List<Tuple6<Long, String, Integer, Integer, String, ItemStatus>> rows = query().project(
                                    ProjectionMappers.select(
                                            Path.of(idRef()),
                                            Path.of(nameRef()),
                                            Path.of(scoreRef()),
                                            Path.of(score2Ref()),
                                            Path.of(noteRef()),
                                            Path.of(statusRef())))
                            .orderBy(idRef())
                            .asc()
                            .list();

                    // oracle 自检：夹具里 score / score2 / note 都含 null，六列期望不是退化的常量表
                    assertThat(expected).extracting(Tuple6::get2).containsNull();
                    assertThat(expected).extracting(Tuple6::get3).containsNull();
                    assertThat(expected).extracting(Tuple6::get4).containsNull();
                    assertThat(rows).containsExactlyElementsOf(expected);
                    // `get(int)` 与 `get0()..get5()` 必须是同一份数据：下标分发写错时这里先红
                    assertThat(rows).allSatisfy(row -> {
                        assertThat(row.size()).isEqualTo(6);
                        assertThat(row.get(0)).isEqualTo(row.get0());
                        assertThat(row.get(1)).isEqualTo(row.get1());
                        assertThat(row.get(2)).isEqualTo(row.get2());
                        assertThat(row.get(3)).isEqualTo(row.get3());
                        assertThat(row.get(4)).isEqualTo(row.get4());
                        assertThat(row.get(5)).isEqualTo(row.get5());
                    });
                }),
                dynamicTest("六列自定义映射器逐字段对位", () -> {
                    List<ItemDetail> expected =
                            fixtureRows().stream().map(ItemDetail::of).toList();
                    List<ItemDetail> rows = query().project(new SixColumnMapper())
                            .orderBy(idRef())
                            .asc()
                            .list();

                    // oracle 自检：相邻的 score / score2 都含 null，错位会被记录比较抓到
                    assertThat(expected).extracting(ItemDetail::score).containsNull();
                    assertThat(expected).extracting(ItemDetail::score2).containsNull();
                    assertThat(expected).extracting(ItemDetail::note).containsNull();
                    assertThat(rows).containsExactlyElementsOf(expected);
                }));
    }

    /// `distinct()` 单列 / 多列 / 映射器四条路径的差分格：期望值全部由 [DistinctOracle] 从夹具原始字段
    /// 自己算出（`LinkedHashSet` 去重），真库一侧只负责渲染 `SELECT DISTINCT`。
    ///
    /// 为什么不用 `@ParameterizedTest`：`nextentity-testing` 的编译类路径上没有 `junit-jupiter-params`，
    /// 参数化只能走 `@TestFactory` + `DynamicTest`（与 `ArithmeticDifferentialTest` 同一写法）。
    ///
    /// 覆盖的语义：
    /// - 单列 `note`：夹具里 `null` 出现两次（id=1、id=4），`SELECT DISTINCT` 把 `null` 算**一组**，
    ///   所以去重后必须含 `null` 且只出现一次；
    /// - 单列 `status`：`NEW` 出现两次（id=1、id=4），去重后按列值升序（`@Enumerated(STRING)` 的字典序）；
    /// - 两列 / 三列：去重作用于**整元组**。夹具里 `score2` 有两行相同（id=1、id=2 都是 10）、`status`
    ///   有两行相同，所以「只按第一列去重」的实现会少行，逐元素比对立刻红；
    /// - 映射器：`distinct()` 对 `project(...)` 路径同样生效（旗标被紧随其后的投影消费，不区分投影形态）。
    @TestFactory
    Stream<DynamicTest> everyDistinctProjectionMatchesMemoryOracle() {
        return Stream.of(
                dynamicTest("单列 note 去重后 NULL 只出现一次", () -> {
                    List<String> expected = DistinctOracle.distinctNotes(fixtureRows());
                    List<String> notes = query().distinct().select(noteRef()).list();

                    // oracle 自检：夹具里 null 出现两次，去重前必然比期望长，这条才测得出「去重没生效」
                    assertThat(DistinctOracle.notes(fixtureRows())).hasSizeGreaterThan(expected.size());
                    assertThat(expected).containsNull().doesNotHaveDuplicates();
                    assertThat(notes).containsExactlyInAnyOrderElementsOf(expected);
                }),
                dynamicTest("单列 status 去重后按列值升序", () -> {
                    List<ItemStatus> expected = DistinctOracle.distinctStatuses(fixtureRows());
                    List<ItemStatus> statuses = query().distinct()
                            .select(statusRef())
                            .orderBy(statusRef())
                            .asc()
                            .list();

                    // oracle 自检：默认夹具的 NEW 出现两次，去重后是这三个值且顺序唯一
                    assertThat(expected).containsExactly(ItemStatus.ACTIVE, ItemStatus.DONE, ItemStatus.NEW);
                    assertThat(statuses).containsExactlyElementsOf(expected);
                }),
                dynamicTest("两列去重作用于整元组", () -> {
                    List<Tuple2<Integer, String>> expected = DistinctOracle.distinctScore2ThenName(fixtureRows());
                    List<Tuple2<Integer, String>> rows = query().distinct()
                            .select(Path.of(score2Ref()), Path.of(nameRef()))
                            .orderBy(nameRef())
                            .asc()
                            .list();

                    // oracle 自检：score2 有重复但整元组不重复，所以期望仍是全表行数
                    assertThat(expected).hasSize(fixtureRows().size()).doesNotHaveDuplicates();
                    assertThat(rows).containsExactlyElementsOf(expected);
                }),
                dynamicTest("三列去重作用于整元组", () -> {
                    List<Tuple3<ItemStatus, String, Integer>> expected =
                            DistinctOracle.distinctStatusNameScore(fixtureRows());
                    List<Tuple3<ItemStatus, String, Integer>> rows = query().distinct()
                            .select(Path.of(statusRef()), Path.of(nameRef()), Path.of(scoreRef()))
                            .orderBy(nameRef())
                            .asc()
                            .list();

                    assertThat(expected).hasSize(fixtureRows().size()).doesNotHaveDuplicates();
                    assertThat(rows).containsExactlyElementsOf(expected);
                }),
                dynamicTest("映射器路径同样去重", () -> {
                    List<String> expected = DistinctOracle.distinctNotes(fixtureRows());
                    List<String> notes =
                            query().distinct().project(new NoteMapper()).list();

                    assertThat(expected).containsNull().doesNotHaveDuplicates();
                    assertThat(notes).containsExactlyInAnyOrderElementsOf(expected);
                }));
    }

    /// `distinct()` 的窗口、计数与聚合：三个口径必须分清，混用任何一个都会红。
    ///
    /// - `SELECT DISTINCT note` 把 `NULL` 算一组 → `data()` 里 `NULL` 占一行；
    /// - `COUNT(DISTINCT note)` **不计** `NULL` → `total()` / `count()` 比去重行数少 1
    ///   （`Slice#total` 明写「可能比 `data()` 少 1 行」）；
    /// - 未去重的 `count()` 是夹具行数。
    ///
    /// 夹具 `note` 去重后 3 行（`null` / `n1` / `n2`）、`COUNT(DISTINCT note)` = 2、未去重 4 行：
    /// 三个数互不相同，所以「`total` 用了未去重行数」与「`total` 用了 `SELECT DISTINCT` 行数」都会红。
    /// `status` 列没有 `NULL`，因此它上面两种口径相同，用来单独钉「窗口切片本身」。
    @TestFactory
    Stream<DynamicTest> everyDistinctWindowAndCountMatchesMemoryOracle() {
        return Stream.of(
                dynamicTest("去重后按列排序的窗口与 total", () -> {
                    List<ItemStatus> ordered = DistinctOracle.distinctStatuses(fixtureRows());
                    Slice<ItemStatus> firstPage = query().distinct()
                            .select(statusRef())
                            .orderBy(statusRef())
                            .asc()
                            .slice(0, 2);
                    Slice<ItemStatus> tailPage = query().distinct()
                            .select(statusRef())
                            .orderBy(statusRef())
                            .asc()
                            .slice(1, 5);
                    Slice<ItemStatus> pastEnd = query().distinct()
                            .select(statusRef())
                            .orderBy(statusRef())
                            .asc()
                            .slice(3, 0);

                    assertThat(ordered).containsExactly(ItemStatus.ACTIVE, ItemStatus.DONE, ItemStatus.NEW);
                    assertThat(firstPage.data()).containsExactlyElementsOf(DistinctOracle.window(ordered, 0, 2));
                    assertThat(tailPage.data()).containsExactlyElementsOf(DistinctOracle.window(ordered, 1, 5));
                    assertThat(pastEnd.data()).isEmpty();
                    assertThat(firstPage.total()).isEqualTo(ordered.size());
                    assertThat(tailPage.total()).isEqualTo(ordered.size());
                    assertThat(pastEnd.total()).isEqualTo(ordered.size());
                }),
                dynamicTest("含 NULL 的列上 total 是 COUNT(DISTINCT) 口径", () -> {
                    List<String> distinctNotes = DistinctOracle.distinctNotes(fixtureRows());
                    Slice<String> page = query().distinct().select(noteRef()).slice(0, 0);

                    // oracle 自检：去重后 3 行（含 1 个 null）、去重前 4 行，三个数互不相同
                    assertThat(distinctNotes).hasSize(3).containsNull();
                    assertThat(DistinctOracle.notes(fixtureRows())).hasSize(4);
                    assertThat(page.data()).containsExactlyInAnyOrderElementsOf(distinctNotes);
                    assertThat(page.total()).isEqualTo(distinctNotes.size() - 1);
                }),
                dynamicTest("count 与 countDistinct 聚合都不计 NULL", () -> {
                    long expectedDistinct = DistinctOracle.distinctNonNullScoreCount(fixtureRows());
                    long expectedNewDistinct = DistinctOracle.distinctNonNullScoreCount(
                            DistinctOracle.withStatus(fixtureRows(), ItemStatus.NEW));
                    long distinctScores = query().distinct().select(scoreRef()).count();
                    long distinctNewScores = query().distinct()
                            .select(scoreRef())
                            .where(statusRef())
                            .eq(ItemStatus.NEW)
                            .count();
                    List<Long> aggregated =
                            query().select(Path.of(scoreRef()).countDistinct()).list();

                    // oracle 自检：score 有一个 null 被排除、且没有重复值，所以去重计数严格少于行数
                    assertThat(expectedDistinct).isLessThan(fixtureRows().size());
                    assertThat(expectedNewDistinct).isEqualTo(1L);
                    assertThat(distinctScores).isEqualTo(expectedDistinct);
                    assertThat(distinctNewScores).isEqualTo(expectedNewDistinct);
                    assertThat(aggregated).containsExactly(expectedDistinct);
                }));
    }

    /// 多列 `distinct()` 上的 `count()` / `slice()` 必须**拒绝**，不能给一个「只数第一列」的错数：
    /// `COUNT(DISTINCT a, b)` 在 MySQL 与 PostgreSQL 形态不同（后者要 `COUNT(DISTINCT (a, b))`）。
    ///
    /// 这里钉的是拒绝分支本身——契约要求任何 `UnsupportedOperationException` 分支都有用例；
    /// 消息只断前缀与列数，不断实体类型名（那是后端特有的）。
    @Test
    void multiColumnDistinctCountAndSliceAreRejected() {
        assertThatThrownBy(() -> query().distinct()
                        .select(Path.of(score2Ref()), Path.of(nameRef()))
                        .count())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining(MULTI_COLUMN_DISTINCT_COUNT_REJECTED)
                .hasMessageContaining("列数 2");
        assertThatThrownBy(() -> query().distinct()
                        .select(Path.of(statusRef()), Path.of(nameRef()), Path.of(scoreRef()))
                        .count())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("列数 3");
        // slice 的 total 依赖 count()，因此同样被拒绝，而不是悄悄按未去重行数给 total
        assertThatThrownBy(() -> query().distinct()
                        .select(Path.of(score2Ref()), Path.of(nameRef()))
                        .slice(0, 1))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining(MULTI_COLUMN_DISTINCT_COUNT_REJECTED);
    }

    /// 自定义映射器的结果：字段与 [ItemViewMapper#expressions()] 的列一一对应。
    ///
    /// @param id     主键
    /// @param name   名称
    /// @param score  数值或 `null`
    /// @param note   文本或 `null`
    /// @param status 枚举状态
    private record ItemView(long id, String name, Integer score, String note, ItemStatus status) {

        /// 按夹具原始字段造期望值：内存侧的唯一来源。
        ///
        /// @param row 夹具原始行
        /// @return 期望的映射结果
        static ItemView of(FixtureRow row) {
            return new ItemView(row.id(), row.name(), row.score(), row.note(), row.status());
        }
    }

    /// 把五列映射成 [ItemView]：下标常量紧邻 [ProjectionMapper#expressions()]，让「列序 ↔ 取值下标」
    /// 的错位在代码审查里看得见；`map` 里的强转同时钉住每列的类型。
    private final class ItemViewMapper implements ProjectionMapper<T, ItemView> {

        private static final int IDX_ID = 0;

        private static final int IDX_NAME = 1;

        private static final int IDX_SCORE = 2;

        private static final int IDX_NOTE = 3;

        private static final int IDX_STATUS = 4;

        @Override
        public List<Expression<T, ?>> expressions() {
            return List.of(
                    Path.of(idRef()),
                    Path.of(nameRef()),
                    Path.of(scoreRef()),
                    Path.of(noteRef()),
                    Path.of(statusRef()));
        }

        @Override
        public ItemView map(Tuple row) {
            return new ItemView(
                    (Long) row.get(IDX_ID),
                    (String) row.get(IDX_NAME),
                    (Integer) row.get(IDX_SCORE),
                    (String) row.get(IDX_NOTE),
                    (ItemStatus) row.get(IDX_STATUS));
        }
    }

    /// 六列映射结果：字段名与列序一一对应（`score2` 紧跟 `score`），让「相邻同类型列错位」
    /// 在失败信息里直接显示成 `expected: score2=5 but was: score2=null`。
    ///
    /// @param id     主键
    /// @param name   名称
    /// @param score  分数
    /// @param score2 第二分数
    /// @param note   备注
    /// @param status 状态
    private record ItemDetail(long id, String name, Integer score, Integer score2, String note, ItemStatus status) {

        /// 内存侧构造：只读夹具原始字段，不经任何生产代码。
        ///
        /// @param row 夹具原始行
        /// @return 期望的映射结果
        static ItemDetail of(FixtureRow row) {
            return new ItemDetail(row.id(), row.name(), row.score(), row.score2(), row.note(), row.status());
        }
    }

    /// 把六列映射成 [ItemDetail]：下标常量紧邻 [ProjectionMapper#expressions()]，
    /// 让「列序 ↔ 取值下标」的错位在审查与失败信息里都看得见；`map` 里的强转同时钉住每列类型。
    private final class SixColumnMapper implements ProjectionMapper<T, ItemDetail> {

        private static final int IDX_ID = 0;

        private static final int IDX_NAME = 1;

        private static final int IDX_SCORE = 2;

        private static final int IDX_SCORE2 = 3;

        private static final int IDX_NOTE = 4;

        private static final int IDX_STATUS = 5;

        @Override
        public List<Expression<T, ?>> expressions() {
            return List.of(
                    Path.of(idRef()),
                    Path.of(nameRef()),
                    Path.of(scoreRef()),
                    Path.of(score2Ref()),
                    Path.of(noteRef()),
                    Path.of(statusRef()));
        }

        @Override
        public ItemDetail map(Tuple row) {
            return new ItemDetail(
                    (Long) row.get(IDX_ID),
                    (String) row.get(IDX_NAME),
                    (Integer) row.get(IDX_SCORE),
                    (Integer) row.get(IDX_SCORE2),
                    (String) row.get(IDX_NOTE),
                    (ItemStatus) row.get(IDX_STATUS));
        }
    }

    /// 单列映射器：`expressions()` 只有 `note` 一列，`map` 取第 0 列。
    ///
    /// 用单列映射器是为了让「`distinct()` 在 `project(...)` 路径上是否生效」与列序无关：
    /// 旗标一旦丢了，夹具里重复的 `null` 就会多出一行，`containsExactlyInAnyOrderElementsOf` 立刻红。
    private final class NoteMapper implements ProjectionMapper<T, String> {

        @Override
        public List<Expression<T, ?>> expressions() {
            return List.of(noteRef());
        }

        @Override
        public String map(Tuple row) {
            return (String) row.get(0);
        }
    }

    /// 内存一侧的投影实现：只读 [FixtureRow] 的原始字段，不经过 nextentity 的任何生产代码。
    ///
    /// 每个方法对应一种「真库怎么投影、内存就怎么拼」的列序：方法名里列的顺序就是期望值的顺序，
    /// 与用例里的 `select(...)` 实参顺序逐字对应，改任一侧都必须同时改另一侧才能保持绿。
    private static final class Oracle {

        private Oracle() {}

        static List<Tuple2<Long, String>> idThenName(List<FixtureRow> rows) {
            return rows.stream().map(row -> new Tuple2<>(row.id(), row.name())).toList();
        }

        static List<Tuple2<String, Long>> nameThenId(List<FixtureRow> rows) {
            return rows.stream().map(row -> new Tuple2<>(row.name(), row.id())).toList();
        }

        static List<Tuple3<Long, String, Integer>> idThenNameThenScore(List<FixtureRow> rows) {
            return rows.stream()
                    .map(row -> new Tuple3<>(row.id(), row.name(), row.score()))
                    .toList();
        }

        static List<Tuple4<Long, String, Integer, ItemStatus>> idThenNameThenScoreThenStatus(List<FixtureRow> rows) {
            return rows.stream()
                    .map(row -> new Tuple4<>(row.id(), row.name(), row.score(), row.status()))
                    .toList();
        }

        /// 六列投影的期望值：**按实参列序** `id, name, score, score2, note, status` 逐列装配。
        ///
        /// 列序即契约：`select` 的第 n 个实参必须落在 `Tuple6` 的第 n 个分量上，
        /// 所以这里用位置化的 `new Tuple6<>(...)` 而不是「按字段名装配」的 map。
        ///
        /// @param rows 夹具原始行
        /// @return 与行一一对应的六列元组
        static List<Tuple6<Long, String, Integer, Integer, String, ItemStatus>> sixColumns(List<FixtureRow> rows) {
            return rows.stream()
                    .map(row -> new Tuple6<>(row.id(), row.name(), row.score(), row.score2(), row.note(), row.status()))
                    .toList();
        }

        /// 单列投影的期望值：`null` 原样保留（不兜默认值）。
        ///
        /// @param rows 夹具原始行
        /// @return 与行一一对应的 `score` 值
        static List<Integer> scores(List<FixtureRow> rows) {
            return rows.stream().map(FixtureRow::score).toList();
        }

        /// `where score >= minimum` 的内存实现：`null` 不满足（SQL 里是 `UNKNOWN`）。
        ///
        /// @param rows    夹具原始行
        /// @param minimum 下界（闭区间）
        /// @return 命中的行，保持夹具顺序
        static List<FixtureRow> withScoreAtLeast(List<FixtureRow> rows, int minimum) {
            return rows.stream()
                    .filter(row -> row.score() != null && row.score() >= minimum)
                    .toList();
        }

        /// `where score is null` 的内存实现。
        ///
        /// @param rows 夹具原始行
        /// @return 命中的行，保持夹具顺序
        static List<FixtureRow> withNullScore(List<FixtureRow> rows) {
            return rows.stream().filter(row -> row.score() == null).toList();
        }

        /// `orderBy name asc` 的内存实现：按 Java 字符串序排，夹具四行互不相同因此顺序唯一。
        ///
        /// @param rows 夹具原始行
        /// @return 按名称升序的行
        static List<FixtureRow> sortedByName(List<FixtureRow> rows) {
            return rows.stream().sorted(Comparator.comparing(FixtureRow::name)).toList();
        }

        /// `list(n)` 的内存实现：截断到前 `count` 行。
        ///
        /// @param values 已排序的期望值
        /// @param count  期望行数
        /// @param <R>    期望值类型
        /// @return 前 `count` 行（不足时返回全部）
        static <R> List<R> first(List<R> values, int count) {
            return values.subList(0, Math.min(count, values.size()));
        }
    }

    /// `distinct()` 一族的内存实现：只读 [FixtureRow] 的原始字段，用 `LinkedHashSet` 做去重。
    ///
    /// 与 [Oracle] 分开是为了让「去重语义」与「列序语义」各自独立演进：改一处不会顺手改到另一处，
    /// 也避免单个嵌套类堆到 PMD `TooManyMethods` 上限。
    ///
    /// 两处 SQL 语义在这里显式建模，而不是交给调用方随手 `.distinct()`：
    /// - `SELECT DISTINCT` 把 `NULL` 算**一组**（[#distinctNotes] 保留 `null`）；
    /// - `COUNT(DISTINCT 列)` **不计** `NULL`（[#distinctNonNullScoreCount] 先滤掉 `null`）。
    private static final class DistinctOracle {

        private DistinctOracle() {}

        /// 未去重的 `note` 列：`null` 原样保留，用于自检「去重前确实更长」。
        ///
        /// @param rows 夹具原始行
        /// @return 与行一一对应的 `note` 值
        static List<String> notes(List<FixtureRow> rows) {
            return rows.stream().map(FixtureRow::note).toList();
        }

        /// `SELECT DISTINCT note` 的内存实现：`null` 算一组，保留首次出现的顺序。
        ///
        /// @param rows 夹具原始行
        /// @return 去重后的值（可能含一个 `null`）
        static List<String> distinctNotes(List<FixtureRow> rows) {
            return notes(rows).stream().distinct().toList();
        }

        /// `SELECT DISTINCT status ORDER BY status` 的内存实现：枚举按 `@Enumerated(STRING)` 的字典序。
        ///
        /// @param rows 夹具原始行
        /// @return 去重后按枚举名升序的状态
        static List<ItemStatus> distinctStatuses(List<FixtureRow> rows) {
            return rows.stream()
                    .map(FixtureRow::status)
                    .distinct()
                    .sorted(Comparator.comparing(ItemStatus::name))
                    .toList();
        }

        /// `SELECT DISTINCT score2, name ORDER BY name` 的内存实现：去重作用于整元组。
        ///
        /// @param rows 夹具原始行
        /// @return 去重后的元组，按名称升序
        static List<Tuple2<Integer, String>> distinctScore2ThenName(List<FixtureRow> rows) {
            return rows.stream()
                    .map(row -> new Tuple2<>(row.score2(), row.name()))
                    .distinct()
                    .sorted(Comparator.comparing(tuple -> tuple.get1()))
                    .toList();
        }

        /// `SELECT DISTINCT status, name, score ORDER BY name` 的内存实现。
        ///
        /// @param rows 夹具原始行
        /// @return 去重后的三元组，按名称升序
        static List<Tuple3<ItemStatus, String, Integer>> distinctStatusNameScore(List<FixtureRow> rows) {
            return rows.stream()
                    .map(row -> new Tuple3<>(row.status(), row.name(), row.score()))
                    .distinct()
                    .sorted(Comparator.comparing(tuple -> tuple.get1()))
                    .toList();
        }

        /// `COUNT(DISTINCT score)` 的内存实现：`null` 不计入。
        ///
        /// @param rows 夹具原始行
        /// @return 去重后的非空 `score` 个数
        static long distinctNonNullScoreCount(List<FixtureRow> rows) {
            return rows.stream()
                    .map(FixtureRow::score)
                    .filter(score -> score != null)
                    .distinct()
                    .count();
        }

        /// `where status = ...` 的内存实现。
        ///
        /// @param rows   夹具原始行
        /// @param status 目标状态
        /// @return 命中的行，保持夹具顺序
        static List<FixtureRow> withStatus(List<FixtureRow> rows, ItemStatus status) {
            return rows.stream().filter(row -> row.status() == status).toList();
        }

        /// `slice(offset, limit)` 的内存实现：在已排序的期望值上切片。
        ///
        /// @param values 已排序的期望值
        /// @param offset 0 基偏移
        /// @param limit  页大小，`0` 表示从 `offset` 起不限行
        /// @param <R>    期望值类型
        /// @return 对应的窗口
        static <R> List<R> window(List<R> values, int offset, int limit) {
            int from = Math.min(offset, values.size());
            int to = limit == 0 ? values.size() : Math.min(from + limit, values.size());
            return values.subList(from, to);
        }
    }
}
