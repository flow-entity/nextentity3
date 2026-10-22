package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.model.Slice;
import org.junit.jupiter.api.Test;

/// 分页一致性：`data()` 与 `total()` 的取值时机。
///
/// `total()` 必须是「按同样条件过滤后的总行数」，不是全表行数，也不是当前页行数。
///
/// @param <T> 后端实体类型
public abstract class PaginationConsistencyTest<T extends Item> extends ConsistencyTestBase<T> {

    /// 首页：data 取一页，total 是过滤后的总行数。
    @Test
    void sliceReturnsPageAndFilteredTotal() {
        Slice<T> slice = query().orderBy(idRef()).asc().slice(0, 2);

        assertThat(slice.data()).extracting(Item::id).containsExactly(APPLE_ID, BANANA_ID);
        assertThat(slice.total()).isEqualTo(4L);
    }

    /// `total` 只统计过滤后的行，不是整表。
    @Test
    void totalCountsFilteredRowsOnly() {
        Slice<T> slice = query().where(scoreRef()).ge(20).orderBy(idRef()).asc().slice(0, 1);

        assertThat(slice.data()).extracting(Item::id).containsExactly(BANANA_ID);
        assertThat(slice.total()).isEqualTo(2L);
    }

    /// 偏移量等于总数时返回空页，不抛异常。
    @Test
    void sliceBeyondLastRowReturnsEmptyData() {
        Slice<T> slice = query().orderBy(idRef()).asc().slice(4, 2);

        assertThat(slice.data()).isEmpty();
    }

    /// 偏移量超过总数时不抛异常。
    @Test
    void sliceFarBeyondTotalReturnsEmptyData() {
        Slice<T> slice = query().orderBy(idRef()).asc().slice(100, 5);

        assertThat(slice.data()).isEmpty();
    }

    /// `to(...)` 把分页结果映射成调用方要的形状（point-mall 的 `Pageable` 依赖它）。
    @Test
    void toMapsWholeSlice() {
        String summary = query().orderBy(idRef())
                .asc()
                .slice(0, 2)
                .to(slice -> slice.total() + "/" + slice.data().size());

        assertThat(summary).isEqualTo("4/2");
    }
}
