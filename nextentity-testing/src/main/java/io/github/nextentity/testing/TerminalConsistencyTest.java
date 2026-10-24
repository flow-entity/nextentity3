package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/// 终止符与排序的一致性：`list` / `first` / `single` / `count` / `exists`。
///
/// 本类只放**有命中**的语义：零行上的同一批终止符见 {@link EmptyTerminalConsistencyTest}，
/// 窗口参数契约（`list(0)` 不限行、`list(负数)` 拒绝）见 {@link WindowArgumentConsistencyTest}。
///
/// @param <T> 后端实体类型
public abstract class TerminalConsistencyTest<T extends Item> extends ConsistencyTestBase<T> {

    /// `list()` 返回全部行。
    ///
    /// 钉**具体 id 集合**而不是 `hasSize(4)`：这是「无条件查询返回全表」的基准用例，
    /// 行数对而内容错（丢一行+重复一行、把 `where` 残留带进无 where 查询）时只有集合断言会红。
    @Test
    void listReturnsAllRows() {
        List<T> rows = query().list();

        assertThat(rows).extracting(Item::id).containsExactlyInAnyOrder(APPLE_ID, BANANA_ID, CHERRY_ID, APPLE_PIE_ID);
    }

    /// `list(limit)` 只截断行数，不改变顺序。
    @Test
    void listWithLimitTruncatesOrderedRows() {
        List<T> rows = query().orderBy(idRef()).asc().list(2);

        assertThat(rows).extracting(Item::id).containsExactly(APPLE_ID, BANANA_ID);
    }

    /// `first()` 取排序后的第一行。
    @Test
    void firstReturnsFirstOrderedRow() {
        T first = query().orderBy(idRef()).desc().first();

        assertThat(first.id()).isEqualTo(APPLE_PIE_ID);
    }

    /// `single()` 在唯一命中时返回该行。
    @Test
    void singleReturnsOnlyRow() {
        T single = query().where(nameRef()).eq(CHERRY_NAME).single();

        assertThat(single.id()).isEqualTo(CHERRY_ID);
    }

    /// `single()` 命中多行时抛 `IllegalStateException`（消息是 api `Collector` 的公开契约）。
    @Test
    void singleThrowsIllegalStateWhenMoreThanOneRow() {
        assertThatThrownBy(() -> query().where(scoreRef()).ge(10).single())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("found more than one result, expected one");
    }

    /// `count()` 统计过滤后的行数。
    @Test
    void countCountsFilteredRows() {
        long count = query().where(scoreRef()).ge(20).count();

        assertThat(count).isEqualTo(2L);
    }

    /// `exists()` 命中为真。
    @Test
    void existsTrueWhenMatch() {
        boolean exists = query().where(nameRef()).contains(BANANA_NAME).exists();

        assertThat(exists).isTrue();
    }

    /// `orderBy(...).asc()` 升序。
    @Test
    void orderByAscIsAscending() {
        List<T> rows = query().orderBy(idRef()).asc().list();

        assertThat(rows).extracting(Item::id).containsExactly(APPLE_ID, BANANA_ID, CHERRY_ID, APPLE_PIE_ID);
    }

    /// `orderBy(...).desc()` 降序。
    @Test
    void orderByDescIsDescending() {
        List<T> rows = query().orderBy(nameRef()).desc().list();

        assertThat(rows).extracting(Item::name).containsExactly(CHERRY_NAME, BANANA_NAME, APPLE_PIE_NAME, APPLE_NAME);
    }
}
