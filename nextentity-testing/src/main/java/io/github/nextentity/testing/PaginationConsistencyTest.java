package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.model.Slice;
import java.util.List;
import org.junit.jupiter.api.Test;

/// 分页一致性：`data()` 与 `total()` 的取值时机，以及窗口参数（`offset` / `limit`）的契约。
///
/// `total()` 必须是「按同样条件过滤后的总行数」，不是全表行数，也不是当前页行数。
/// `list(int)` 与 `slice(offset, limit)` 的 `0` 都表示「不限行」，负数都在构造窗口时被拒绝。
///
/// @param <T> 后端实体类型
public abstract class PaginationConsistencyTest<T extends Item> extends ConsistencyTestBase<T> {

    /// 窗口参数被拒时消息里点名的字段名（`Window` 紧凑构造器逐个点名）。
    private static final String OFFSET = "offset";

    private static final String LIMIT = "limit";

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

    /// `limit = 0` 表示「从 `offset` 起不限行数」（`SliceStep#slice` 的公开契约）。
    ///
    /// 与 `list(0)` 是一对：两处 `0` 都表示「不限行」，而不是「取 0 行」。
    /// 写成 `LIMIT 0` 的实现会返回空页，调用方看到的是「这一页没数据」而不是「最后一页之后」，
    /// 而 `total` 仍是对的——只断 `total` 的用例完全看不出来。
    @Test
    void sliceWithZeroLimitReturnsEverythingFromOffset() {
        Slice<T> slice = query().orderBy(idRef()).asc().slice(1, 0);

        assertThat(slice.data()).extracting(Item::id).containsExactly(BANANA_ID, CHERRY_ID, APPLE_PIE_ID);
        assertThat(slice.total()).isEqualTo(4L);
    }

    /// 空结果上的分页：`data` 为空、`total` 为 `0`，且不抛异常。
    @Test
    void sliceOnEmptyResultReportsZeroTotal() {
        Slice<T> slice = query().where(nameRef()).eq(MISSING_NAME).slice(0, 2);

        assertThat(slice.data()).isEmpty();
        assertThat(slice.total()).isZero();
    }

    /// 负数参数在构造窗口时即被拒绝，且消息点名是哪个参数（`Window` 的紧凑构造器）。
    ///
    /// 不拒绝的后果不是异常而是**静默错页**：`offset = -1` 交给 JDBC 会变成
    /// 「从第 0 行起」或驱动直接抛 SQL 异常，两种行为都不在契约里。
    @Test
    void sliceRejectsNegativeOffsetAndLimit() {
        assertThatThrownBy(() -> query().slice(-1, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(OFFSET);
        assertThatThrownBy(() -> query().slice(0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(LIMIT);
    }

    /// `list(0)` 表示「不限行」，不是「取 0 行」（`Collector#list(int)` 的公开契约）。
    ///
    /// 渲染成 `LIMIT 0` 的实现在这里立刻露出来：夹具四行会变成空列表。
    @Test
    void listWithZeroLimitMeansUnbounded() {
        List<T> rows = query().orderBy(idRef()).asc().list(0);

        assertThat(rows).extracting(Item::id).containsExactly(APPLE_ID, BANANA_ID, CHERRY_ID, APPLE_PIE_ID);
    }

    /// `list(负数)` 抛 `IllegalArgumentException`（`Window` 紧凑构造器拒绝）。
    @Test
    void listRejectsNegativeLimit() {
        assertThatThrownBy(() -> query().list(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(LIMIT);
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
