package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.BaseWhereStep;
import io.github.nextentity.api.model.Slice;
import io.github.nextentity.api.model.Tuple3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// **分页重组**变形关系：整序列表必须等于「逐页切片的串联」。
///
/// 这条关系为什么值得单独成一族：分页的期望值依赖 `offset/limit/total` 三者的一致口径，逐格手算
/// 就要为每页算一次边界，人一多就算错；而「逐页取完再拼起来应该等于不分页」是**全称成立**的，
/// 不需要任何人知道正确答案是几行。它抓的是 `offset` 错位、`limit+1` 多取、`total` 与 `count()`
/// 口径不一致、末页截断、以及「切片之间重复或漏行」这五类错。
///
/// 页大小 `PAGE_SIZE` 与夹具默认行数（64）互质，末页必然不是整页——整页时「多取一行/少取一行」
/// 这类错位在拼接结果里看不出来，关系会假绿。
///
/// @param <T> 后端实体类型
public abstract class MetamorphicPagingTest<T extends Item> extends MetamorphicTestBase<T> {

    /// 页大小与默认行数（64）互质，最后一页不是整页——整页时「多取/少取一行」这类错位看不出来。
    static final int PAGE_SIZE = 7;

    @Test
    @DisplayName("变形：整序列表等于逐页切片的串联，且每页 total 与 count() 同口径")
    void paginationSlicesReassembleTheOrderedList() {
        for (Named<T> base : basePredicates()) {
            BaseWhereStep<T, T> ordered =
                    query().where(base.predicate()).orderBy(idRef()).asc();
            List<Long> whole = ordered.list().stream().map(Item::id).toList();
            List<Long> reassembled = new ArrayList<>();
            for (int offset = 0; offset < whole.size() + PAGE_SIZE; offset += PAGE_SIZE) {
                Slice<T> page = ordered.slice(offset, PAGE_SIZE);
                assertThat(page.total())
                        .as("%s：第 %d 页的 total 必须与 count() 一致", base.name(), offset / PAGE_SIZE)
                        .isEqualTo(ordered.count());
                page.data().forEach(item -> reassembled.add(item.id()));
            }
            assertThat(reassembled).as("%s：逐页串联必须还原整序列表", base.name()).containsExactlyElementsOf(whole);
        }
    }

    @Test
    @DisplayName("变形：倒序结果必须恰好是正序结果的逆序")
    void descendingOrderIsTheReverseOfAscending() {
        for (Named<T> base : basePredicates()) {
            List<Long> ascending = ids(query().where(base.predicate()));
            List<Long> descending = query().where(base.predicate()).orderBy(idRef()).desc().list().stream()
                    .map(Item::id)
                    .toList();
            assertThat(descending)
                    .as("%s：倒序必须是正序的逆序（`ORDER BY` 被整段丢弃或 `ASC`/`DESC` 写反时这条会红）", base.name())
                    .containsExactlyElementsOf(ascending.reversed());
        }
    }

    @Test
    @DisplayName("变形：投影列随分页走时，逐页串联的元组必须等于整序列表自己算出的元组")
    void projectedColumnsSurvivePagingAndStayAligned() {
        // 分页重组只比 id 时，`select` 漏列、多列、列序错位全都看不见——id 照样能拼回去。
        // 这一格把三列投影的结果与「从整条实体上直接读字段」算出的元组逐行对齐比较，
        // 期望值不来自任何手算常量，而是来自同一个夹具的另一条读路径。
        for (Named<T> base : basePredicates()) {
            List<T> whole =
                    query().where(base.predicate()).orderBy(idRef()).asc().list();
            List<Tuple3<Long, String, Integer>> expected = new ArrayList<>();
            for (T item : whole) {
                expected.add(new Tuple3<>(item.id(), item.name(), item.score()));
            }
            BaseWhereStep<T, Tuple3<Long, String, Integer>> projected = query().select(idRef(), nameRef(), scoreRef())
                    .where(base.predicate())
                    .orderBy(idRef())
                    .asc();
            List<Tuple3<Long, String, Integer>> actual = new ArrayList<>();
            for (int offset = 0; offset < expected.size() + PAGE_SIZE; offset += PAGE_SIZE) {
                actual.addAll(projected.slice(offset, PAGE_SIZE).data());
            }
            assertThat(actual).as("%s：投影列必须随分页一起走，且列序与实体字段一致", base.name()).containsExactlyElementsOf(expected);
        }
    }

    @Test
    @DisplayName("变形：count() 与 list().size() 同口径")
    void countAgreesWithListSize() {
        for (Named<T> base : basePredicates()) {
            assertThat(query().where(base.predicate()).count())
                    .as("%s：count() 必须等于 list().size()", base.name())
                    .isEqualTo(query().where(base.predicate()).list().size());
        }
    }
}
