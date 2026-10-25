package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.api.model.Tuple2;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/// 关联嵌套路径上的**完整谓词矩阵**与**投影**：真库一侧逐格构造查询，内存一侧逐格求值。
///
/// 每一格同时给出两半：「真库怎么查」与「内存怎么判」。两半不共享代码——内存那半只读
/// {@link AssetFixture} 的原始值（见 {@link AssociationJoinOracle}），所以一格变红就是
/// 「生产侧渲染」与「SQL 语义」对不上，而不是「我照抄了生产实现」。
///
/// `alpha` / `beta` 由基类递进来（来自夹具的唯一真身），本类自己不写字面量：
/// 否则「查询参数」与「夹具」会各自漂移，谓词格会证明一个不存在的夹具。
///
/// @param <T> 后端实体类型
final class AssociationPredicateProbe<T extends Asset> {

    /// 用例名前缀：报表里一眼看出这是嵌套路径那一族。
    private static final String NESTED = "nested ";

    /// 谓词格的名字。格子名即语义：红的时候不用点开代码就知道是哪个算子出的问题。
    private static final String EQ_ALPHA = "eq(alpha)";

    private static final String NE_ALPHA = "ne(alpha)";

    private static final String IN_ALPHA_BETA = "in([alpha, beta])";

    private static final String IN_EMPTY = "in([])";

    private static final String NOT_IN_ALPHA = "notIn([alpha])";

    private static final String NOT_IN_EMPTY = "notIn([])";

    private static final String IS_NULL = "isNull()";

    private static final String IS_NOT_NULL = "isNotNull()";

    private static final String CONTAINS_AM = "contains(am)";

    private static final String CONTAINS_EMPTY = "contains()";

    private static final String LIKE_AL_PREFIX = "like(al%)";

    private static final String LIKE_UNDERSCORE = "like(a___)";

    private static final String GT_ALPHA = "gt(alpha)";

    private static final String GE_BETA = "ge(beta)";

    private static final String LT_BETA = "lt(beta)";

    private static final String LE_ALPHA = "le(alpha)";

    private static final String BETWEEN_ALPHA_BETA = "between(alpha, beta)";

    /// `like` 的两种通配写法。同一个模式串在「真库怎么查」与「内存怎么判」两处各出现一次，
    /// 就地重复会被 PMD 的 `AvoidDuplicateLiterals`（阈值 2）拦，所以集中到一处。
    private static final String LIKE_AL = "al%";

    /// 下划线通配格的模式串：四字符、以 `a` 开头，只命中 `alps`，集合 `{7}`。
    ///
    /// 早先用的是 `_lpha`：它只命中 `alpha`，集合 `{1,2}` 与 `eq(alpha)` **完全相同**——
    /// 那样把 `like` 渲染成 `=` 全绿。换成 `a___` 后与任何比较格都不同集合。
    private static final String LIKE_UNDERSCORE_CHARS = "a___";

    /// 子串格用的固定子串：只出现在 `amber` 里，命中集合 `{6}`。
    ///
    /// 取值有讲究：早先用的是 `beta` 的中间三字符 `bet`，命中集合 `{3}` 与 `ge(beta)` **完全相同**——
    /// 那样把 `contains` 渲染成 `>=` 全绿。换成 `am` 后命中 `{6}`，17 格里没有别格是这个集合。
    private static final String CONTAINS_INFIX = "am";

    private final List<AssetFixture> rows;

    private final Supplier<EntityQuery<T>> query;

    private final StringPath<T> ownerNamePath;

    private final PathRef<T, Long> idRef;

    private final String alpha;

    private final String beta;

    AssociationPredicateProbe(
            List<AssetFixture> rows,
            Supplier<EntityQuery<T>> query,
            StringPath<T> ownerNamePath,
            PathRef<T, Long> idRef,
            String alpha,
            String beta) {
        this.rows = rows;
        this.query = query;
        this.ownerNamePath = ownerNamePath;
        this.idRef = idRef;
        this.alpha = alpha;
        this.beta = beta;
    }

    /// 17 个谓词格，一格一条动态用例。
    ///
    /// @return 每个谓词格一条动态用例
    Stream<DynamicTest> predicateTests() {
        return runs().stream().map(run -> DynamicTest.dynamicTest(NESTED + run.label(), () -> assertRun(run)));
    }

    /// 17 个谓词格。每格同时给出「真库怎么查」与「内存怎么判」，两者是同一个格子的两半，不共享代码。
    ///
    /// @return 谓词格列表
    List<CellRun<T>> runs() {
        // 同一段嵌套路径的两种静态视图：`where(StringPath)` 返回的算子只带
        // `eq`/`ne`/`in`/`notIn`/`isNull`/`isNotNull` 与字符串算子，**没有** `gt`/`ge`/`lt`/`le`/`between`；
        // 要落在字符串列上做大小比较，只能把同一对象按更宽的 `Path` 传进 `where(...)`
        // （两个重载里 `StringPath` 更具体，所以静态类型必须是 `Path` 才会选到另一个）。
        var path = ownerNamePath;
        Path<T, String> plain = path;
        List<CellRun<T>> runs = new ArrayList<>();
        runs.add(new CellRun<>(
                EQ_ALPHA, false, () -> query.get().where(path).eq(alpha).list(), value -> alpha.equals(value)));
        runs.add(new CellRun<>(
                NE_ALPHA,
                false,
                () -> query.get().where(path).ne(alpha).list(),
                value -> !alpha.equals(value) && value != null));
        runs.add(new CellRun<>(
                IN_ALPHA_BETA,
                false,
                () -> query.get().where(path).in(List.of(alpha, beta)).list(),
                value -> alpha.equals(value) || beta.equals(value)));
        runs.add(new CellRun<>(
                IN_EMPTY, true, () -> query.get().where(path).in(List.of()).list(), value -> false));
        runs.add(new CellRun<>(
                NOT_IN_ALPHA,
                false,
                () -> query.get().where(path).notIn(List.of(alpha)).list(),
                value -> !alpha.equals(value) && value != null));
        runs.add(new CellRun<>(
                NOT_IN_EMPTY,
                true,
                () -> query.get().where(path).notIn(List.of()).list(),
                value -> true));
        runs.add(new CellRun<>(
                IS_NULL, false, () -> query.get().where(path).isNull().list(), value -> value == null));
        runs.add(new CellRun<>(
                IS_NOT_NULL, false, () -> query.get().where(path).isNotNull().list(), value -> value != null));
        runs.add(new CellRun<>(
                CONTAINS_AM,
                false,
                () -> query.get().where(path).contains(CONTAINS_INFIX).list(),
                value -> value != null && value.contains(CONTAINS_INFIX)));
        runs.add(new CellRun<>(
                CONTAINS_EMPTY,
                false,
                () -> query.get().where(path).contains("").list(),
                value -> value != null));
        runs.add(new CellRun<>(
                LIKE_AL_PREFIX,
                false,
                () -> query.get().where(path).like(LIKE_AL).list(),
                value -> value != null && AssociationJoinOracle.like(value, LIKE_AL)));
        runs.add(new CellRun<>(
                LIKE_UNDERSCORE,
                false,
                () -> query.get().where(path).like(LIKE_UNDERSCORE_CHARS).list(),
                value -> value != null && AssociationJoinOracle.like(value, LIKE_UNDERSCORE_CHARS)));
        runs.add(new CellRun<>(
                GT_ALPHA,
                false,
                () -> query.get().where(plain).gt(alpha).list(),
                value -> value != null && value.compareTo(alpha) > 0));
        runs.add(new CellRun<>(
                GE_BETA,
                false,
                () -> query.get().where(plain).ge(beta).list(),
                value -> value != null && value.compareTo(beta) >= 0));
        runs.add(new CellRun<>(
                LT_BETA,
                false,
                () -> query.get().where(plain).lt(beta).list(),
                value -> value != null && value.compareTo(beta) < 0));
        runs.add(new CellRun<>(
                LE_ALPHA,
                false,
                () -> query.get().where(plain).le(alpha).list(),
                value -> value != null && value.compareTo(alpha) <= 0));
        runs.add(new CellRun<>(
                BETWEEN_ALPHA_BETA,
                false,
                () -> query.get().where(plain).between(alpha, beta).list(),
                value -> value != null && value.compareTo(alpha) >= 0 && value.compareTo(beta) <= 0));
        return runs;
    }

    /// 谓词格的**可区分性**自检：把每格的期望集合算出来，断言"本该互相区分"的配对确实不同。
    ///
    /// 这不是在验证生产代码，而是在验证**夹具够不够刁**。夹具只有 alpha/beta 两个非空取值时：
    /// `gt(alpha)` 与 `ne(alpha)` 都只命中 beta 那行、`le(alpha)` 与 `eq(alpha)` 都是 alpha 那两行、
    /// `between(alpha,beta)` 等于 `isNotNull`、`like("al%")` 与 `like("_lpha")` 同集合——
    /// 于是把 `gt` 渲染成 `<>`、把 `between` 渲染成 `is not null`、把 `_` 通配丢掉，17 个格子全绿。
    /// 每个格子的"非空 / 非全表"自检挡不住这种退化，必须逐对比较期望集合。
    ///
    /// 七行夹具下 17 格**只剩两组**同义反复，且都是语义本等价：`ne(alpha)` ≡ `notIn([alpha])`、
    /// `contains()` ≡ `isNotNull()`。其余 13 格的期望集合两两不同（本方法末尾按名字断言这两组
    /// 就是全部重复——不写"去重后应有 N 种"这类裸数字，那种断言每加一格都要手改，而且撞车时
    /// 只报"数量不对"、不点名是哪两格撞了）。
    ///
    /// 配对覆盖两类退化：**同一族内**（`le`→`=`、`gt`→`<>`、`ge`→`gt`、`lt`→`le`、`between`→`is not null`）
    /// 与**跨族**（`contains`→`>=`、`_` 通配→前缀匹配）。
    void assertCellsAreDistinguishable() {
        Map<String, Set<Long>> expected = new LinkedHashMap<>();
        for (CellRun<T> run : runs()) {
            expected.put(run.label(), AssociationJoinOracle.matchingIds(rows, run.matches()));
        }
        List<List<String>> pairs = List.of(
                // `le` 退化成 `=`：需要夹具里存在 alpha 的下界取值（aardvark）
                List.of(EQ_ALPHA, LE_ALPHA),
                // `gt` 退化成 `<>`：需要下界取值
                List.of(NE_ALPHA, GT_ALPHA),
                // `ge(beta)` 退化成 `gt(alpha)`：需要 alpha 与 beta 之间的取值（amber）
                List.of(GT_ALPHA, GE_BETA),
                // `lt(beta)` 退化成 `le(alpha)`：需要 alpha 与 beta 之间的取值
                List.of(LE_ALPHA, LT_BETA),
                // `between` 退化成 `is not null`：需要 [alpha, beta] 之外的取值（aardvark）
                List.of(BETWEEN_ALPHA_BETA, IS_NOT_NULL),
                // `_` 通配被当成前缀匹配：需要共享前缀但长度不同的取值（alps）
                List.of(LIKE_AL_PREFIX, LIKE_UNDERSCORE),
                // `like` 被渲染成 `=`：模式串必须不落在任何比较格的集合上（a___ vs alpha）
                List.of(EQ_ALPHA, LIKE_UNDERSCORE),
                // `contains` 被渲染成 `>=`：子串格与比较格必须命中不同集合（amber vs beta）
                List.of(CONTAINS_AM, GE_BETA));
        for (List<String> pair : pairs) {
            String left = pair.get(0);
            String right = pair.get(1);
            assertThat(expected.get(left))
                    .as("夹具退化：`%s` 与 `%s` 的期望集合相同，这一对算子互相区分不了", left, right)
                    .isNotEqualTo(expected.get(right));
        }
        // 逐对断言只覆盖"配对表里点到的退化"。这一条从整体上兜住：按名字断言"重复的格组"
        // **就是**这两组语义本等价的同义反复——夹具退化或多加一格撞车都会在这里红，
        // 而且失败消息直接点名是哪几格撞在一起。组内按名字排序，免得断言依赖格子声明顺序。
        Map<Set<Long>, List<String>> byExpected = new LinkedHashMap<>();
        expected.forEach((label, ids) ->
                byExpected.computeIfAbsent(ids, key -> new ArrayList<>()).add(label));
        assertThat(byExpected.values().stream()
                        .filter(labels -> labels.size() > 1)
                        .map(labels -> labels.stream().sorted().toList())
                        .toList())
                .as("17 格里允许重复的只有这两组语义本等价的同义反复；出现别的重复说明夹具退化或新加的格子撞车了")
                .containsExactlyInAnyOrder(List.of(NE_ALPHA, NOT_IN_ALPHA), List.of(CONTAINS_EMPTY, IS_NOT_NULL));
    }

    /// 一格的自检：先用内存 oracle 算出期望集合，再与真库结果逐元素比对。
    ///
    /// @param run 谓词格
    void assertRun(CellRun<T> run) {
        Set<Long> expected = AssociationJoinOracle.matchingIds(rows, run.matches());
        if (!run.constantFolded()) {
            // oracle 自检（不是恒真断言）：命中集合既不能为空、也不能是全表，否则这一格证明不了渲染对不对。
            // `in([])` / `notIn([])` 被 api 折成恒假 / 恒真常量，期望必然为空 / 全表，故不参与自检，
            // 但它们**仍然**与真库结果逐行比对——「空集合渲染成 `IN ()` 而不是 `1 = 0`」正好由它们抓。
            assertThat(expected).as("%s 的期望集合不能为空", run.label()).isNotEmpty();
            assertThat(expected).as("%s 的期望集合不能是全表", run.label()).isNotEqualTo(AssociationJoinOracle.allIds(rows));
        }
        assertThat(AssociationJoinOracle.ids(run.rows().get()))
                .as(NESTED + "%s", run.label())
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    /// LEFT JOIN 语义的专条判据：`isNull` 恰好命中关联为空的那行，`eq`/`ne`/`in`/`notIn` 都不含它，
    /// 且 `eq ∪ ne` 不是全表（`NULL = 'alpha'` 与 `NULL <> 'alpha'` 都是 `UNKNOWN`）。
    void assertLeftJoinSemantics() {
        var path = ownerNamePath;
        Set<Long> nullRow = nullRowId();
        Set<Long> equal = AssociationJoinOracle.matchingIds(rows, alpha::equals);
        Set<Long> different = AssociationJoinOracle.matchingIds(rows, value -> !alpha.equals(value) && value != null);
        Set<Long> inOwners =
                AssociationJoinOracle.matchingIds(rows, value -> alpha.equals(value) || beta.equals(value));
        Set<Long> all = AssociationJoinOracle.allIds(rows);
        // oracle 自检（不是恒真断言）：夹具里必须真有 `null` 关联行，否则下面这些
        // `doesNotContainAnyElementsOf(nullRow)` 全部空转，这一族根本证明不了 LEFT JOIN 语义。
        assertThat(nullRow).as("夹具必须含 null 关联行").isNotEmpty();

        assertThat(AssociationJoinOracle.ids(query.get().where(path).isNull().list()))
                .as("`isNull()` 只命中关联为空的行（内连接会把它丢掉）")
                .containsExactlyInAnyOrderElementsOf(nullRow);
        assertThat(AssociationJoinOracle.ids(query.get().where(path).eq(alpha).list()))
                .as("`eq(alpha)` 不含关联为空的行")
                .doesNotContainAnyElementsOf(nullRow);
        assertThat(AssociationJoinOracle.ids(query.get().where(path).ne(alpha).list()))
                .as("`ne(alpha)` 不含关联为空的行：`NULL <> 'alpha'` 是 UNKNOWN")
                .doesNotContainAnyElementsOf(nullRow);
        assertThat(AssociationJoinOracle.ids(
                        query.get().where(path).in(List.of(alpha, beta)).list()))
                .as("`in([alpha, beta])` 不含关联为空的行")
                .doesNotContainAnyElementsOf(nullRow);
        assertThat(AssociationJoinOracle.ids(
                        query.get().where(path).notIn(List.of(alpha)).list()))
                .as("`notIn([alpha])` 不含关联为空的行")
                .doesNotContainAnyElementsOf(nullRow);

        Set<Long> union = new LinkedHashSet<>(equal);
        union.addAll(different);
        assertThat(union).as("`eq` 与 `ne` 的并集不是全表：UNKNOWN 行两侧都不命中").isNotEqualTo(all);
        assertThat(different).as("`ne(alpha)` 的期望集合也不是全表").isNotEqualTo(all);
        assertThat(inOwners).as("`in([alpha, beta])` 的期望集合也不是全表").isNotEqualTo(all);
    }

    /// 关联为空的那些行的主键：夹具里 `ownerName` 为 `null` 的行。
    ///
    /// @return 主键集合
    Set<Long> nullRowId() {
        Set<Long> ids = new LinkedHashSet<>();
        for (AssetFixture row : rows) {
            if (row.ownerName() == null) {
                ids.add(row.id());
            }
        }
        return ids;
    }

    /// 嵌套路径投影：`select(owner.name)` 与 `select(id, owner.name)` 两条，逐元素比对（含 `null`）。
    ///
    /// 用 `containsExactlyElementsOf` 而不是过滤掉 `null` 再比：投影少一列、或 `null` 被换成空串，都是要抓的错。
    ///
    /// @return 每个投影形态一条动态用例
    Stream<DynamicTest> projectionTests() {
        Path<T, String> plain = ownerNamePath;
        List<String> expectedNames = AssociationJoinOracle.ownerNames(rows);
        List<Tuple2<Long, String>> expectedPairs = rows.stream()
                .map(row -> new Tuple2<Long, String>(row.id(), row.ownerName()))
                .toList();
        return Stream.of(
                DynamicTest.dynamicTest("select(owner.name)", () -> assertThat(
                                query.get().select(plain).orderBy(idRef).asc().list())
                        .as("`select(owner.name)` 的列值序列（含 `null`）")
                        .containsExactlyElementsOf(expectedNames)),
                DynamicTest.dynamicTest("select(id, owner.name)", () -> assertThat(query.get()
                                .select(Path.of(idRef), plain)
                                .orderBy(idRef)
                                .asc()
                                .list())
                        .as("`select(id, owner.name)` 的列值序列（含 `null`）")
                        .containsExactlyElementsOf(expectedPairs)));
    }

    /// 谓词矩阵的一格：用例名 + 「是否被折成布尔常量」标记 + 真库查询 + 内存判据。
    ///
    /// @param label          用例名（进报表）
    /// @param constantFolded 是否被 api 折成布尔常量（`in([])` / `notIn([])`）
    /// @param rows           真库一侧的查询
    /// @param matches        内存一侧的判据（参数是 join 出来的 owner 名称，可为 `null`）
    /// @param <T>            后端实体类型
    private record CellRun<T>(
            String label, boolean constantFolded, Supplier<List<T>> rows, Predicate<String> matches) {}
}
