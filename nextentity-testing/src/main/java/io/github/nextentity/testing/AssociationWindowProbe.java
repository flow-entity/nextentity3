package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.api.model.Slice;
import io.github.nextentity.api.model.Tuple2;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/// 关联嵌套路径上的**完整排序序列**与**分页语义**：真库一侧发查询，内存一侧只对夹具原始值排序 / 切片。
///
/// 排序用例钉的是**完整序列**（`containsExactly`，含 `null`），不是「非空部分有序」：
/// 后者对「`null` 行被排到哪、还是根本没参与排序」没有任何约束。
///
/// 断言用 `(id, owner.name)` 两列投影而不是实体：中立接口 [Asset] 只暴露 `id` / `name`，
/// 刻意不暴露关联（避免触发懒加载），所以「排序结果里 `null` 落在第几位」只能靠这两列读出来。
///
/// @param <T> 后端实体类型
final class AssociationWindowProbe<T extends Asset> {

    /// `null` 关联行在 asc 下的位置**由后端方言决定**（默认 MySQL：排最前；PostgreSQL：排最后），
    /// 期望序列由 {@link AssociationJoinOracle#orderedPairs} 按 {@link NullOrdering} 算出，
    /// 所以用例名不再写死「null 在前」，否则换方言后名字会撒谎。断言仍是**完整序列**，
    /// 不放宽成「非空部分有序」——放宽之后 `null` 行排错位置就再没人管了。
    private static final String ORDER_ASC_FULL_SEQUENCE = "orderBy(owner.name) asc 的完整序列（含 null 关联行）";

    /// desc 下 `null` 关联行的位置（同一口径的另一面）。
    private static final String ORDER_DESC_FULL_SEQUENCE = "orderBy(owner.name) desc 的完整序列（含 null 关联行）";

    /// join 列升序 + 主键**降序**：同一 owner 的两行必须跟着主键走。
    ///
    /// 这一格专门抓「第二个 `orderBy` 被丢掉」：丢掉之后 `alpha` 两行的顺序变成数据库自由选择，
    /// 而 asc 那格可能碰巧还是对的。
    private static final String ORDER_OWNER_THEN_ID_DESC = "orderBy(owner.name asc, id desc) 的 tiebreaker";

    /// `offset=0` 且 `limit` 未取满：首页 + 总数。
    private static final String SLICE_FIRST_PAGE = "slice(0, 2) 首页";

    /// `limit` 超过剩余行数：一次拿全，`total` 仍是实体行数。
    private static final String SLICE_LIMIT_OVERFLOWS = "slice(0, 99) limit 超过剩余行数";

    /// `offset` 恰好等于总行数：空页，但 `total` 不变。
    private static final String SLICE_OFFSET_AT_END = "slice(size, 2) offset 越界";

    /// `offset + limit` 跨过尾部：拿到的是尾巴，`total` 不变。
    private static final String SLICE_TAIL = "slice(2, 99) offset 与 limit 跨过尾部";

    /// 嵌套条件 + 分页：`total` 是**满足嵌套条件的实体行数**，不因 join 放大（关联是 1:1，
    /// 若渲染成 join 后再 `COUNT(*)` 会数成 join 后的行数；这里夹具恰好 1:1，所以用 `isNull`
    /// 把 7 行收窄到 6 行，`total` 必须是 6 而不是 7）。
    private static final String SLICE_WITH_NESTED_CONDITION = "where(owner.name isNotNull) 之后分页";

    private final List<AssetFixture> rows;

    private final Supplier<EntityQuery<T>> query;

    private final StringPath<T> ownerNamePath;

    private final PathRef<T, Long> idRef;

    /// 后端升序时 `null` 关联的相对位置（方言特性，由 {@link AssociationDifferentialTest} 的构造器传入）。
    private final NullOrdering nullOrdering;

    AssociationWindowProbe(
            List<AssetFixture> rows,
            Supplier<EntityQuery<T>> query,
            StringPath<T> ownerNamePath,
            PathRef<T, Long> idRef,
            NullOrdering nullOrdering) {
        this.rows = rows;
        this.query = query;
        this.ownerNamePath = ownerNamePath;
        this.idRef = idRef;
        this.nullOrdering = nullOrdering;
    }

    /// 三条排序用例：asc 完整序列、desc 完整序列、join 列升序 + 主键降序的双键完整序列。
    ///
    /// @return 每条排序形态一条动态用例
    Stream<DynamicTest> orderTests() {
        List<Tuple2<Long, String>> ascending = AssociationJoinOracle.orderedPairs(rows, nullOrdering, true, true);
        List<Tuple2<Long, String>> descending = AssociationJoinOracle.orderedPairs(rows, nullOrdering, false, true);
        List<Tuple2<Long, String>> tieBroken = AssociationJoinOracle.orderedPairs(rows, nullOrdering, true, false);
        return Stream.of(
                DynamicTest.dynamicTest(ORDER_ASC_FULL_SEQUENCE, () -> assertThat(query.get()
                                .select(idRef, ownerNamePath)
                                .orderBy(ownerNamePath)
                                .asc()
                                .orderBy(idRef)
                                .asc()
                                .list())
                        .as("`orderBy(owner.name, id) asc` 的完整序列（含 null）")
                        .containsExactlyElementsOf(ascending)),
                DynamicTest.dynamicTest(ORDER_DESC_FULL_SEQUENCE, () -> assertThat(query.get()
                                .select(idRef, ownerNamePath)
                                .orderBy(ownerNamePath)
                                .desc()
                                .orderBy(idRef)
                                .asc()
                                .list())
                        .as("`orderBy(owner.name desc, id asc)` 的完整序列（含 null）")
                        .containsExactlyElementsOf(descending)),
                DynamicTest.dynamicTest(ORDER_OWNER_THEN_ID_DESC, () -> assertThat(query.get()
                                .select(idRef, ownerNamePath)
                                .orderBy(ownerNamePath)
                                .asc()
                                .orderBy(idRef)
                                .desc()
                                .list())
                        .as("`orderBy(owner.name asc, id desc)`：同一 owner 的行按主键降序")
                        .containsExactlyElementsOf(tieBroken)));
    }

    /// 五条分页用例：首页、`limit` 溢出、`offset` 越界、`offset + limit` 跨尾部、嵌套条件后的分页。
    ///
    /// 每格都同时钉 `total` 与主键集合：`total` 必须是**实体行数**（join 不放大），`data` 必须是主键集合
    /// （不是 `hasSize`——`hasSize(2)` 对「返回了哪两行」毫无约束）。
    ///
    /// @return 每个分页形态一条动态用例
    Stream<DynamicTest> sliceTests() {
        List<Long> all = new ArrayList<>(AssociationJoinOracle.fixtureIds(rows));
        int size = all.size();
        return Stream.of(
                DynamicTest.dynamicTest(
                        SLICE_FIRST_PAGE,
                        () -> assertPage(query.get().orderBy(idRef).asc().slice(0, 2), size, firstOf(all, 2))),
                DynamicTest.dynamicTest(
                        SLICE_LIMIT_OVERFLOWS,
                        () -> assertPage(query.get().orderBy(idRef).asc().slice(0, 99), size, firstOf(all, 99))),
                DynamicTest.dynamicTest(
                        SLICE_OFFSET_AT_END,
                        () -> assertPage(query.get().orderBy(idRef).asc().slice(size, 2), size, List.of())),
                DynamicTest.dynamicTest(
                        SLICE_TAIL,
                        () -> assertPage(query.get().orderBy(idRef).asc().slice(2, 99), size, tailOf(all, 2, 99))),
                DynamicTest.dynamicTest(SLICE_WITH_NESTED_CONDITION, () -> {
                    // 期望侧完全由夹具算：命中集合来自 oracle 的三值逻辑，不读任何后端结果。
                    List<Long> matched =
                            new ArrayList<>(AssociationJoinOracle.matchingIds(rows, value -> value != null));
                    assertThat(matched.size()).as("oracle 自检：嵌套条件必须收窄结果").isLessThan(size);
                    assertPage(
                            query.get()
                                    .where(ownerNamePath.isNotNull())
                                    .orderBy(idRef)
                                    .asc()
                                    .slice(1, 2),
                            matched.size(),
                            tailOf(matched, 1, 2));
                }));
    }

    /// 一条分页断言的收口：`total` + 主键序列。
    ///
    /// @param page        真库返回的页
    /// @param total       期望的 `total`（= 满足条件的实体行数）
    /// @param expectedIds 期望的主键序列
    private static void assertPage(Slice<? extends Asset> page, long total, List<Long> expectedIds) {
        assertThat(page.total()).as("`total` 必须是实体行数，不因 join 放大").isEqualTo(total);
        assertThat(new ArrayList<>(AssociationJoinOracle.ids(page.data())))
                .as("分页结果的主键序列")
                .containsExactlyElementsOf(expectedIds);
    }

    /// 从主键序列头部取至多 `limit` 行。
    ///
    /// @param ids   全量主键序列
    /// @param limit 至多取几行
    /// @return 该页的主键序列
    private static List<Long> firstOf(List<Long> ids, int limit) {
        return tailOf(ids, 0, limit);
    }

    /// 从主键序列的第 `offset` 行起取至多 `limit` 行。
    ///
    /// @param ids    全量主键序列
    /// @param offset 跳过的行数
    /// @param limit  至多取几行
    /// @return 该页的主键序列
    private static List<Long> tailOf(List<Long> ids, int offset, int limit) {
        List<Long> page = new ArrayList<>();
        for (int index = offset; index < ids.size() && index < offset + limit; index++) {
            page.add(ids.get(index));
        }
        return page;
    }
}
