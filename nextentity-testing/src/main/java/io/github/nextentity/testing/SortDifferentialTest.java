package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.BaseWhereStep;
import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.PathRef;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/// 排序家族的**差分** oracle：单键升降序、多键累积、以及 `null` 参与排序时的位置，
/// 每格都拿「真库返回的 id 序列」与「内存里自己排出的 id 序列」逐元素比对。
///
/// 为什么是逐元素而不是集合相等：排序错的是**顺序**，集合相等恰好把顺序丢了。
/// 键写反、方向写反、多键被实现成「只认最后一键」、`null` 位置不同，这四类错误在
/// `containsExactlyInAnyOrder` 下全部通过，在 `containsExactlyElementsOf` 下立刻红。
///
/// 差分分工（两条路径不相交）：
/// - 真库一侧：`select(idRef()).orderBy(...).asc()/desc()` 累积成 `ORDER BY`；
/// - 内存一侧：{@link Key#ascending()} 直接读 {@link Fixtures#ITEM_ROWS} 的原始值自己排，
///   不经过 nextentity 的任何生产代码，也不读后端返回顺序。
///
/// **`null` 的排序位置（本基类钉的口径）**：`null` 视为最小值，即升序在前、降序在后。
/// 这条不是猜的，是两套装置逐格实测一致的结果（H2 `MODE=MySQL` 与 MySQL 8.0.18 上
/// `score` / `score2` / `note` 三个可空列同结果）：
/// ```text
/// score  asc  → 4,1,2,3   （id=4 的 score 是 null，排最前）
/// score  desc → 3,2,1,4   （null 排最后）
/// score2 asc  → 3,4,1,2   （id=3 的 score2 是 null，排最前）
/// note   asc  → 1,4,2,3   （id=1、4 的 note 是 null，排最前；组内按 id）
/// ```
/// 注意这条口径**属于方言语义**，不是 SQL 标准：PostgreSQL 默认是「升序 null 在后」，
/// 与这里相反。所以本基类钉的是「MySQL 家族（含 H2 的 MySQL 兼容模式）把 null 当最小值」，
/// 换到 null 排序相反的后端时，正确做法是按方言覆盖该格（或把它移进后端特定用例），
/// 而不是放宽断言。
///
/// **枚举键按存储文本排序**：`status` 在 JPA 侧是 `@Enumerated(STRING)`（与 point-mall 的
/// 50/51 处映射一致），因此库里的顺序是 `ACTIVE < DONE < NEW`（字典序），**不是**枚举的
/// 声明顺序 `NEW(0) < ACTIVE(1) < DONE(2)`。内存一侧用 `status().name()` 比较，就是为了跟存储形态对齐：
/// 若照 `Enum#compareTo`（序数）写，这一格会红，而红的原因会指向「实现错了」，其实是 oracle 错了。
///
/// **大小写不进矩阵**：夹具名称全小写，所以 H2 的大小写敏感排序与 MySQL 默认
/// `utf8mb4_0900_ai_ci` 的忽略大小写排序在这份数据上恰好一致。混大小写的排序是方言差异，
/// 不进后端中立基类。
///
/// @param <T> 后端实体类型
public abstract class SortDifferentialTest<T extends Item> extends ConsistencyTestBase<T> {

    /// 每格一条动态用例：一条排序规格 = 一次真库执行 + 一次内存排序。
    ///
    /// 用 `@TestFactory` 而不是 `@ParameterizedTest`：`junit-jupiter-params` 不在
    /// `nextentity-testing` 的编译类路径上（本模块只声明 `junit-jupiter-api`），改 pom 是红线。
    ///
    /// @return 每条排序规格一条动态用例
    @TestFactory
    Stream<DynamicTest> everySortSpecMatchesMemoryOrder() {
        return SPECS.stream().map(spec -> DynamicTest.dynamicTest(spec.name(), () -> assertSpec(spec)));
    }

    private void assertSpec(SortSpec spec) {
        List<FixtureRow> expectedRows =
                Fixtures.ITEM_ROWS.stream().sorted(comparator(spec)).toList();
        assertTotalOrder(spec, expectedRows);
        List<Long> expected = expectedRows.stream().map(FixtureRow::id).toList();

        assertThat(actualIds(spec)).as("排序规格 %s 的返回顺序", spec.name()).containsExactlyElementsOf(expected);
    }

    /// oracle 自检：规格在夹具上必须构成**全序**。
    ///
    /// 若相邻两行在该规格下相等（例如只按 `status` 排序时 id=1 与 id=4 同为 `NEW`），
    /// 库返回它们的先后就是未定义的：这类格子写进用例只会随执行计划翻红翻绿，
    /// 而红的时候指向的是「实现不稳定」，不是「实现错了」。所以这里直接把它变成一条明确的失败：
    /// 要么补一个 tiebreaker 键（本矩阵里的 `id`），要么删掉该规格。
    ///
    /// @param spec 排序规格
    /// @param expectedRows 按该规格排好的行
    private void assertTotalOrder(SortSpec spec, List<FixtureRow> expectedRows) {
        for (int index = 1; index < expectedRows.size(); index++) {
            FixtureRow previous = expectedRows.get(index - 1);
            FixtureRow current = expectedRows.get(index);
            assertThat(comparator(spec).compare(previous, current))
                    .as(
                            "排序规格 %s 在 id=%s 与 id=%s 之间没有分出先后：SQL 的 tie 顺序未定义，请补 tiebreaker",
                            spec.name(), previous.id(), current.id())
                    .isNegative();
        }
    }

    /// 真库一侧：把规格累积成 `ORDER BY`，只取 id 列，避免把实体装配差异混进来。
    ///
    /// @param spec 排序规格
    /// @return 真库返回的 id 序列
    private List<Long> actualIds(SortSpec spec) {
        EntityQuery<T> query = query();
        BaseWhereStep<T, Long> step = query.select(idRef());
        for (Order order : spec.orders()) {
            step = ordered(step, order);
        }
        return step.list();
    }

    private BaseWhereStep<T, Long> ordered(BaseWhereStep<T, Long> step, Order order) {
        return switch (order.key()) {
            case ID -> direction(step, idRef(), order.ascending());
            case NAME -> direction(step, nameRef(), order.ascending());
            case SCORE -> direction(step, scoreRef(), order.ascending());
            case SCORE2 -> direction(step, score2Ref(), order.ascending());
            case NOTE -> direction(step, noteRef(), order.ascending());
            case STATUS -> direction(step, statusRef(), order.ascending());
        };
    }

    private BaseWhereStep<T, Long> direction(
            BaseWhereStep<T, Long> step, PathRef<T, ? extends Comparable<?>> ref, boolean ascending) {
        return ascending ? step.orderBy(ref).asc() : step.orderBy(ref).desc();
    }

    /// 内存一侧：按规格把键依次串成比较器。
    ///
    /// 降序用 `reversed()` 而不是另写一份比较器：整序取反后，`nullsFirst` 自然变成
    /// 「降序时 null 在后」，与实测的库行为一致，也不用维护两套 null 规则。
    ///
    /// @param spec 排序规格
    /// @return 该规格在夹具上的比较器
    private static Comparator<FixtureRow> comparator(SortSpec spec) {
        Comparator<FixtureRow> combined = null;
        for (Order order : spec.orders()) {
            Comparator<FixtureRow> keyComparator = order.ascending()
                    ? order.key().ascending()
                    : order.key().ascending().reversed();
            combined = combined == null ? keyComparator : combined.thenComparing(keyComparator);
        }
        return combined;
    }

    private static SortSpec spec(String name, Order... orders) {
        return new SortSpec(name, List.of(orders));
    }

    /// 排序矩阵：16 格。
    ///
    /// 挑格原则是「能切开写错的方向」：
    /// - 单键升降各两列（`score`、`score2`），两个可空列分别覆盖「首键为 null」；
    /// - `note` / `status` 这两列带并列值，因此配 `id` 当 tiebreaker，专门钉「多键累积」；
    /// - `score2-asc/id-desc` 与 `score2-desc/id-asc` 两格是「主键有并列、次键方向相反」的组合：
    ///   只按主键排（忽略次键）会给出另一种顺序，能切开「只认最后一键」的实现；
    /// - `status-asc/score-desc` 与 `status-desc/score-asc` 把 `null` 放到**次键**上，
    ///   钉住「null 位置在每一键上都成立」，而不只是首键。
    private static final List<SortSpec> SPECS = List.of(
            spec("id-asc", new Order(Key.ID, true)),
            spec("id-desc", new Order(Key.ID, false)),
            spec("score-asc", new Order(Key.SCORE, true)),
            spec("score-desc", new Order(Key.SCORE, false)),
            spec("score2-asc/id-asc", new Order(Key.SCORE2, true), new Order(Key.ID, true)),
            spec("score2-desc/id-asc", new Order(Key.SCORE2, false), new Order(Key.ID, true)),
            spec("score2-asc/id-desc", new Order(Key.SCORE2, true), new Order(Key.ID, false)),
            spec("note-asc/id-asc", new Order(Key.NOTE, true), new Order(Key.ID, true)),
            spec("note-desc/id-asc", new Order(Key.NOTE, false), new Order(Key.ID, true)),
            spec("name-asc/id-asc", new Order(Key.NAME, true), new Order(Key.ID, true)),
            spec("name-desc/id-asc", new Order(Key.NAME, false), new Order(Key.ID, true)),
            spec("status-asc/id-asc", new Order(Key.STATUS, true), new Order(Key.ID, true)),
            spec("status-desc/id-asc", new Order(Key.STATUS, false), new Order(Key.ID, true)),
            spec(
                    "status-asc/score-desc/id-asc",
                    new Order(Key.STATUS, true),
                    new Order(Key.SCORE, false),
                    new Order(Key.ID, true)),
            spec(
                    "status-desc/score-asc/id-asc",
                    new Order(Key.STATUS, false),
                    new Order(Key.SCORE, true),
                    new Order(Key.ID, true)),
            spec("note-asc/score-desc", new Order(Key.NOTE, true), new Order(Key.SCORE, false)));

    /// 可排序的列。内存一侧的比较器按**存储形态**取值，见类注释里 `status` 那一段。
    enum Key {
        ID,
        NAME,
        SCORE,
        SCORE2,
        NOTE,
        STATUS;

        /// 该列升序的比较器（可空列把 `null` 当最小值）。
        ///
        /// @return 升序比较器
        Comparator<FixtureRow> ascending() {
            return switch (this) {
                case ID -> Comparator.comparing(FixtureRow::id);
                case NAME -> Comparator.comparing(FixtureRow::name);
                case SCORE -> Comparator.comparing(FixtureRow::score, Comparator.nullsFirst(Comparator.naturalOrder()));
                case SCORE2 ->
                    Comparator.comparing(FixtureRow::score2, Comparator.nullsFirst(Comparator.naturalOrder()));
                case NOTE -> Comparator.comparing(FixtureRow::note, Comparator.nullsFirst(Comparator.naturalOrder()));
                case STATUS -> Comparator.comparing(row -> row.status().name());
            };
        }
    }

    /// 一个排序键 + 方向。
    ///
    /// @param key 排序键
    /// @param ascending `true` 为升序
    record Order(Key key, boolean ascending) {}

    /// 一条排序规格：报表里的名字 + 按累积顺序排列的键。
    ///
    /// @param name 报表里显示的名字
    /// @param orders 排序键，按 `ORDER BY` 的累积顺序
    record SortSpec(String name, List<Order> orders) {}
}
