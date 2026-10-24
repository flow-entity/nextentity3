package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/// 终止符与排序的一致性：`list` / `first` / `single` / `count` / `exists`。
///
/// 窗口参数的契约（`list(0)` 不限行、`list(负数)` 拒绝）见 {@link PaginationConsistencyTest}，
/// 零行聚合见 {@link EmptyResultConsistencyTest}。
///
/// @param <T> 后端实体类型
public abstract class TerminalConsistencyTest<T extends Item> extends ConsistencyTestBase<T> {

    /// `list()` 返回全部行。
    @Test
    void listReturnsAllRows() {
        List<T> rows = query().list();

        assertThat(rows).hasSize(4);
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

    /// `first()` 无命中返回 `null`（不是抛异常）。
    @Test
    void firstReturnsNullWhenNothingMatches() {
        T first = query().where(nameRef()).eq(MISSING_NAME).first();

        assertThat(first).isNull();
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

    /// `count()` 无命中返回 `0`（不是抛异常、不是 `null`）。
    ///
    /// 与 `first()` 无命中返回 `null` 是一对：`count()` 的返回类型是 `long`，
    /// 「没有行」只能表达成 `0`，实现若把无结果当成错误就与契约相反。
    @Test
    void countReturnsZeroWhenNothingMatches() {
        long count = query().where(nameRef()).eq(MISSING_NAME).count();

        assertThat(count).isZero();
    }

    /// `exists()` 命中为真。
    @Test
    void existsTrueWhenMatch() {
        boolean exists = query().where(nameRef()).contains(BANANA_NAME).exists();

        assertThat(exists).isTrue();
    }

    /// `exists()` 无命中为假。
    @Test
    void existsFalseWhenNoMatch() {
        boolean exists = query().where(nameRef()).eq(MISSING_NAME).exists();

        assertThat(exists).isFalse();
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
