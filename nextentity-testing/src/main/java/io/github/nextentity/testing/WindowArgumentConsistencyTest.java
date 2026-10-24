package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.model.Slice;
import java.util.List;
import org.junit.jupiter.api.Test;

/// 窗口参数（`offset` / `limit`）的契约：`0` 表示「不限行」，负数在构造窗口时即被拒。
///
/// 与 {@link PaginationConsistencyTest} 分家的理由是这两族证伪方式不同：那一族钉 `data()` / `total()`
/// 的取值时机（分页**算得对不对**，靠夹具行数就能证伪），这一族钉参数本身合不合法（**非法输入有没有被
/// 静默吞掉**，只有实现漏掉校验时才会红）。
///
/// `limit = 0` 是最容易被写错的一格：`LIMIT 0` 在 SQL 里是「取 0 行」，而契约是「不限行」，
/// 两者都返回「一页数据」，只看 `total` 的用例区分不出来。
///
/// @param <T> 后端实体类型
public abstract class WindowArgumentConsistencyTest<T extends Item> extends ConsistencyTestBase<T> {

    /// 窗口参数被拒时消息里点名的字段名（`Window` 紧凑构造器逐个点名）。
    private static final String OFFSET = "offset";

    private static final String LIMIT = "limit";

    /// `limit = 0` 表示「从 `offset` 起不限行数」（`SliceStep#slice` 的公开契约）。
    ///
    /// 写成 `LIMIT 0` 的实现会返回空页，调用方看到的是「这一页没数据」而不是「最后一页之后」，
    /// 而 `total` 仍是对的——只断 `total` 的用例完全看不出来。
    @Test
    void sliceWithZeroLimitReturnsEverythingFromOffset() {
        Slice<T> slice = query().orderBy(idRef()).asc().slice(1, 0);

        assertThat(slice.data()).extracting(Item::id).containsExactly(BANANA_ID, CHERRY_ID, APPLE_PIE_ID);
        assertThat(slice.total()).isEqualTo(4L);
    }

    /// 越界与 `limit = 0` 的组合：`offset` 等于总数时是空页，`0` 仍然表示「不限行」而不是「非法」。
    ///
    /// 这条与上一条互为对照：上一条证明 `0` 不被当成 0 行，这一条证明「不越界判断」没有被 `0` 短路掉。
    @Test
    void sliceAtTotalWithZeroLimitReturnsEmptyData() {
        Slice<T> slice = query().orderBy(idRef()).asc().slice(4, 0);

        assertThat(slice.data()).isEmpty();
        assertThat(slice.total()).isEqualTo(4L);
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

    /// 负数校验**先于** `total <= offset` 的短路：`slice(4, -1)` 在夹具上正好落在短路分支里
    /// （`total = 4`），校验若留在短路之后，非法参数会被当成「空页」静默吞掉。
    ///
    /// 这是契约（`SliceStep#slice` 无条件承诺 `IllegalArgumentException`）与实际实现之间
    /// 唯一的缝：只测 `slice(0, -1)` 的用例永远踩不到它。
    @Test
    void sliceRejectsNegativeArgsEvenWhenOffsetIsBeyondLastRow() {
        assertThatThrownBy(() -> query().slice(4, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(LIMIT);
        assertThatThrownBy(() -> query().slice(100, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(LIMIT);
        assertThatThrownBy(() -> query().slice(-1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(OFFSET);
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
}
