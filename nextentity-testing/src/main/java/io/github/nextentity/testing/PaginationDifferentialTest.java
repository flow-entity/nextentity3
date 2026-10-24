package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.BaseWhereStep;
import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.model.Slice;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/// 分页的**差分** oracle：真库执行 `list(n)` / `slice(offset, limit)` / `first()`，
/// 内存侧对**按同一 `orderBy` 排好序的行列表**做 `subList` 切片，两边逐元素比对 `data`，并单独核对 `total`。
///
/// 为什么不复用 `PaginationConsistencyTest` 的手算常量：那里的期望值只有一种来源（人脑），
/// `total` 与 `data` 混在一起算错时，常量常常跟着一起错。这里两条路径不相交：
/// - 真库一侧：`ORDER BY` + `LIMIT`/`OFFSET` 由 Hibernate 生成并执行，`total` 来自独立的 count 查询；
/// - 内存一侧：[Pages] 只读 {@link Fixtures#ITEM_ROWS} 的字段，自己排序、自己 `subList`，
///   不调 nextentity 的任何生产代码，也不回读后端结果。
///
/// 本类专门钉这几类 bug（每一类都能在这里被切成红）：
/// - **`total` 把窗口混进去**：`total` 返回「当前页行数」或「`total - offset`」而不是过滤后的总行数。
///   同一 `where` 下 `total()` 必须与 `count()` 相等，且对每个 `offset`/`limit` 组合都不变；
/// - **窗口边界**：`offset = 0`、`offset` 正好等于总数、`offset` 远超总数、`limit` 大于剩余行数、
///   `limit = 0`（不限行）；
/// - **截断与排序的先后**：`LIMIT` 先于 `ORDER BY` 时，取到的是「某几行」而不是「排序后的前几行」；
/// - **空结果**：`data` 空、`total` 为 `0`、`first()` 为 `null`，都不抛异常。
///
/// 窗口参数本身合不合法（负数被拒）不在这里：那属于 [WindowArgumentConsistencyTest]。
///
/// @param <T> 后端实体类型
public abstract class PaginationDifferentialTest<T extends Item> extends ConsistencyTestBase<T> {

    /// 过滤条件的下界（`where score >= 20` 命中夹具的 id=2、3 两行）。
    private static final int SCORE_THRESHOLD = 20;

    /// 一页窗口：`limit = 0` 表示「从 `offset` 起不限行」（`SliceStep#slice` 的公开契约）。
    private static final List<Window> WINDOWS = List.of(
            new Window(0, 2),
            new Window(1, 2),
            new Window(0, 4),
            new Window(2, 0),
            new Window(3, 10),
            new Window(4, 2),
            new Window(100, 5),
            new Window(0, 10));

    /// `list(n)` 的行数档位：`0` 表示不限行，`4` 正好等于总数，`10` 大于总数。
    private static final List<Integer> LIMITS = List.of(0, 1, 2, 3, 4, 10);

    /// 每个窗口一条用例：`data` 与内存侧对同一份排序结果做的 `subList` 逐元素相等。
    ///
    /// 用 `@TestFactory` 而不是 `@ParameterizedTest`：`junit-jupiter-params` 不在
    /// `nextentity-testing` 的编译类路径上（本模块只声明 `junit-jupiter-api`），改 pom 是红线。
    ///
    /// @return 每个窗口一条动态用例
    @TestFactory
    Stream<DynamicTest> everySliceWindowMatchesMemoryWindow() {
        return WINDOWS.stream()
                .map(window -> DynamicTest.dynamicTest("slice " + window.offset() + "/" + window.limit(), () -> {
                    Slice<T> slice = orderedBy(Scope.ALL).slice(window.offset(), window.limit());
                    List<Long> expected = Pages.window(Fixtures.ITEM_ROWS, window);

                    // oracle 自检：窗口越界当且仅当 offset 已到总数之后，空页不能是「实现漏了一大截」。
                    assertThat(expected.isEmpty()).isEqualTo(window.offset() >= Fixtures.ITEM_ROWS.size());
                    assertThat(slice.data()).extracting(Item::id).containsExactlyElementsOf(expected);
                }));
    }

    /// 每个行数档位一条用例：`list(n)` 等于「排序后前 n 行」，`n = 0` 等于全部行。
    ///
    /// 典型 bug：`list(n)` 渲染成 `LIMIT n` 之前先做了 `Set` 去重/重排，或把 `0` 渲染成 `LIMIT 0`。
    ///
    /// @return 每个行数档位一条动态用例
    @TestFactory
    Stream<DynamicTest> everyListLimitMatchesMemoryPrefix() {
        return LIMITS.stream()
                .map(limit -> DynamicTest.dynamicTest("list " + limit, () -> {
                    List<T> rows = orderedBy(Scope.ALL).list(limit);
                    List<Long> expected = Pages.window(Fixtures.ITEM_ROWS, new Window(0, limit));

                    // oracle 自检：`0` = 不限行（全部 4 行），其余取 min(n, 总数)，期望值不得为空。
                    assertThat(expected)
                            .hasSize(
                                    limit == 0
                                            ? Fixtures.ITEM_ROWS.size()
                                            : Math.min(limit, Fixtures.ITEM_ROWS.size()));
                    assertThat(rows).extracting(Item::id).containsExactlyElementsOf(expected);
                }));
    }

    /// 每个「过滤条件 × 窗口」一条用例：`total()` 与 `count()` 同口径，且与 `offset`/`limit` 无关。
    ///
    /// 这是分页最常见的 bug 的正面证据：`total` 若返回「当前页行数」「剩余行数」或「整表行数」，
    /// 在 `offset`/`limit` 变化时必然与 `count()` 分家；本用例把三种过滤条件与 8 个窗口全部跑一遍。
    ///
    /// @return 每个过滤条件与窗口的组合一条动态用例
    @TestFactory
    Stream<DynamicTest> everyWindowTotalMatchesCountAndMemory() {
        return Stream.of(Scope.values()).flatMap(scope -> WINDOWS.stream()
                .map(window ->
                        DynamicTest.dynamicTest(scope.name() + " " + window.offset() + "/" + window.limit(), () -> {
                            List<FixtureRow> memory = memoryRows(scope);
                            long counted = scopedQuery(scope).count();
                            Slice<T> slice = orderedBy(scope).slice(window.offset(), window.limit());

                            assertThat(memory).hasSizeLessThanOrEqualTo(Fixtures.ITEM_ROWS.size());
                            assertThat(counted).isEqualTo(memory.size());
                            assertThat(slice.total()).isEqualTo(memory.size());
                        })));
    }

    /// 分页 + `where` + 多键 `orderBy`：内存侧自己过滤、自己按「`score` 降序、`name` 升序」排序再切片。
    ///
    /// 多键排序的第二键只有在第一键并列时才起作用，因此这条同时钉住「`orderBy` 只保留最后一个键」
    /// 与「后一个 `orderBy` 覆盖前一个」两种实现错误（夹具里 `score` 三行互不相同，第二键不改变顺序，
    /// 但整体的 `data` 必须与内存切片逐元素相同）。
    ///
    /// @return 每个窗口一条动态用例
    @TestFactory
    Stream<DynamicTest> filteredMultiKeyPaginationMatchesMemory() {
        return WINDOWS.stream()
                .map(window -> DynamicTest.dynamicTest("filtered " + window.offset() + "/" + window.limit(), () -> {
                    List<FixtureRow> filtered = Pages.withScorePresent(Fixtures.ITEM_ROWS);
                    List<Long> expected = Pages.window(Pages.scoredByDescThenName(filtered), window);
                    List<T> data = query().where(scoreRef())
                            .isNotNull()
                            .orderBy(scoreRef())
                            .desc()
                            .orderBy(nameRef())
                            .asc()
                            .slice(window.offset(), window.limit())
                            .data();

                    // oracle 自检：过滤真的去掉了一行，排序也不是夹具原序。
                    assertThat(filtered).isNotEmpty().hasSizeLessThan(Fixtures.ITEM_ROWS.size());
                    assertThat(Pages.ids(Pages.scoredByDescThenName(filtered))).isNotEqualTo(Pages.ids(filtered));
                    assertThat(data).extracting(Item::id).containsExactlyElementsOf(expected);
                }));
    }

    /// 空结果上的分页：`data` 空、`total` 为 `0`、`count()` 为 `0`、`first()` 为 `null`，都不抛异常。
    ///
    /// 与「窗口越界」那条的区别：那里总数大于 0 只是窗口落在表尾之后，这里总数本身就是 0——
    /// `total` 若写成「至少 1 行」或 `first()` 对空页抛异常，只有这一条能抓到。
    ///
    /// @return 每个窗口一条动态用例
    @TestFactory
    Stream<DynamicTest> emptyResultPaginationMatchesMemory() {
        return WINDOWS.stream()
                .map(window -> DynamicTest.dynamicTest("empty " + window.offset() + "/" + window.limit(), () -> {
                    Slice<T> slice = scopedQuery(Scope.MISSING_NAME).slice(window.offset(), window.limit());

                    assertThat(memoryRows(Scope.MISSING_NAME)).isEmpty();
                    assertThat(slice.data()).isEmpty();
                    assertThat(slice.total()).isZero();
                    assertThat(scopedQuery(Scope.MISSING_NAME).count()).isZero();
                    assertThat(scopedQuery(Scope.MISSING_NAME).first()).isNull();
                }));
    }

    /// `first()` 等于内存切片的唯一元素：升序取首行、降序取末行、过滤 + 多键排序取内存首行。
    ///
    /// `first()` 常被实现成「`list(1)` 的第一行」或「不加 `ORDER BY` 的第一行」，
    /// 前者在 `limit` 与 `offset` 混用时错位，后者在夹具顺序与索引顺序不同时分家。
    @Test
    void firstMatchesMemoryHead() {
        List<Long> ascending = Pages.ids(Fixtures.ITEM_ROWS);
        List<Long> descending = ascending.reversed();
        List<Long> scoredFirst = Pages.ids(Pages.scoredByDescThenName(Pages.withScorePresent(Fixtures.ITEM_ROWS)));

        assertThat(ascending).isNotEmpty();
        assertThat(descending).isNotEmpty();
        assertThat(scoredFirst).isNotEmpty();
        assertThat(query().orderBy(idRef()).asc().first().id()).isEqualTo(ascending.getFirst());
        assertThat(query().orderBy(idRef()).desc().first().id()).isEqualTo(descending.getFirst());
        assertThat(query().where(scoreRef())
                        .isNotNull()
                        .orderBy(scoreRef())
                        .desc()
                        .orderBy(nameRef())
                        .asc()
                        .first()
                        .id())
                .isEqualTo(scoredFirst.getFirst());
    }

    /// 按过滤条件造查询入口（每次全新，条件不跨用例累积）。
    ///
    /// @param scope 过滤条件
    /// @return 已应用过滤条件的查询入口
    private BaseWhereStep<T, T> scopedQuery(Scope scope) {
        EntityQuery<T> fresh = query();
        return switch (scope) {
            case ALL -> fresh;
            case SCORE_AT_LEAST_THRESHOLD -> fresh.where(scoreRef()).ge(SCORE_THRESHOLD);
            case MISSING_NAME -> fresh.where(nameRef()).eq(MISSING_NAME);
        };
    }

    /// 按过滤条件造「已按主键升序」的查询入口。
    ///
    /// @param scope 过滤条件
    /// @return 已应用过滤与排序的查询入口
    private BaseWhereStep<T, T> orderedBy(Scope scope) {
        return scopedQuery(scope).orderBy(idRef()).asc();
    }

    /// 同一过滤条件在内存侧的期望行：只读夹具字段自己筛，不经过后端。
    ///
    /// @param scope 过滤条件
    /// @return 期望行，保持夹具顺序
    private List<FixtureRow> memoryRows(Scope scope) {
        return switch (scope) {
            case ALL -> Fixtures.ITEM_ROWS;
            case SCORE_AT_LEAST_THRESHOLD -> Pages.withScoreAtLeast(Fixtures.ITEM_ROWS, SCORE_THRESHOLD);
            case MISSING_NAME -> Pages.withName(Fixtures.ITEM_ROWS, MISSING_NAME);
        };
    }

    /// 三种过滤条件：不过滤、`score >= 20`、名称不存在（空结果）。
    private enum Scope {
        ALL,
        SCORE_AT_LEAST_THRESHOLD,
        MISSING_NAME
    }

    /// 一页窗口。
    ///
    /// @param offset 起始行（0 起）
    /// @param limit  行数上限；`0` 表示不限行
    private record Window(int offset, int limit) {}

    /// 内存一侧的分页实现：排序与 `subList` 全在这里，只读 [FixtureRow] 的原始字段。
    ///
    /// 每个方法都对应用例里的一条真库查询，方法名点明「怎么排、怎么切」，与查询逐字对应。
    private static final class Pages {

        private Pages() {}

        /// 主键序列（夹具已按 id 升序）。
        ///
        /// @param rows 夹具原始行
        /// @return 主键列表
        static List<Long> ids(List<FixtureRow> rows) {
            return rows.stream().map(FixtureRow::id).toList();
        }

        /// 在一份**已排好序**的行上切出窗口：`offset` 越界退化为空页，`limit = 0` 表示到末尾为止。
        ///
        /// 这就是 `OFFSET`/`LIMIT` 的语义，也是期望 `data` 的唯一来源；`total` 不从这里推导。
        ///
        /// @param rows   已排序的夹具行
        /// @param window 窗口
        /// @return 该窗口内的主键
        static List<Long> window(List<FixtureRow> rows, Window window) {
            List<Long> ids = ids(rows);
            int from = Math.min(window.offset(), ids.size());
            int to = window.limit() == 0 ? ids.size() : Math.min(from + window.limit(), ids.size());
            return ids.subList(from, to);
        }

        /// `where score >= minimum` 的内存实现：`null` 不满足（SQL 里是 `UNKNOWN`）。
        ///
        /// @param rows    夹具原始行
        /// @param minimum 下界（闭区间）
        /// @return 命中的行
        static List<FixtureRow> withScoreAtLeast(List<FixtureRow> rows, int minimum) {
            return rows.stream()
                    .filter(row -> row.score() != null && row.score() >= minimum)
                    .toList();
        }

        /// `where score is not null` 的内存实现。
        ///
        /// @param rows 夹具原始行
        /// @return 命中的行
        static List<FixtureRow> withScorePresent(List<FixtureRow> rows) {
            return rows.stream().filter(row -> row.score() != null).toList();
        }

        /// `where name = candidate` 的内存实现（用于「无命中 → 空页」）。
        ///
        /// @param rows      夹具原始行
        /// @param candidate 目标名称
        /// @return 命中的行
        static List<FixtureRow> withName(List<FixtureRow> rows, String candidate) {
            return rows.stream().filter(row -> candidate.equals(row.name())).toList();
        }

        /// `orderBy score desc, name asc` 的内存实现：先按 `score` 降序，并列时按 `name` 升序。
        ///
        /// @param rows 已过滤掉 `score` 为 `null` 的行
        /// @return 排序后的行
        static List<FixtureRow> scoredByDescThenName(List<FixtureRow> rows) {
            return rows.stream()
                    .sorted(Comparator.comparing(FixtureRow::score).reversed().thenComparing(FixtureRow::name))
                    .toList();
        }
    }
}
