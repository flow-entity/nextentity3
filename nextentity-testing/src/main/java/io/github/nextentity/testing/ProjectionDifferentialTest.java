package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Expression;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.ProjectionMapper;
import io.github.nextentity.api.ProjectionMappers;
import io.github.nextentity.api.model.Tuple;
import io.github.nextentity.api.model.Tuple2;
import io.github.nextentity.api.model.Tuple3;
import io.github.nextentity.api.model.Tuple4;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

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
///   而不是只断言对象非空。
///
/// @param <T> 后端实体类型
public abstract class ProjectionDifferentialTest<T extends Item> extends ConsistencyTestBase<T> {

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
}
