package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/// 零行上的终止符语义：`list` / `count` / `first` / `single` / `exists` 各自怎么表达「没有命中」。
///
/// 五个终止符的返回类型不同，「没有行」只能各自表达：`list` → 空列表、`count` → `0`、
/// `first` / `single` → `null`、`exists` → `false`。任何一个实现把「没有行」当成错误抛出，
/// 或把 `null` 兜成默认值，都会在这里露出来。
///
/// 零行上的**聚合**见 {@link EmptyAggregateConsistencyTest}，分页上的空集见
/// {@link WindowArgumentConsistencyTest}。
///
/// @param <T> 后端实体类型
public abstract class EmptyTerminalConsistencyTest<T extends Item> extends ConsistencyTestBase<T> {

    /// `list()` 无行时是空列表（对比：同一条件下的 `count()` 是一行 `0`）。
    @Test
    void listReturnsEmptyWhenNothingMatches() {
        List<T> rows = query().where(nameRef()).eq(MISSING_NAME).list();

        assertThat(rows).isEmpty();
    }

    /// `count()` 无行时是 `0`：返回类型是 `long`，「没有行」只能这么表达。
    @Test
    void countReturnsZeroWhenNothingMatches() {
        long count = query().where(nameRef()).eq(MISSING_NAME).count();

        assertThat(count).isZero();
    }

    /// `first()` 无行时是 `null`，不抛异常。
    @Test
    void firstReturnsNullWhenNothingMatches() {
        T first = query().where(nameRef()).eq(MISSING_NAME).first();

        assertThat(first).isNull();
    }

    /// `single()` 无行时也是 `null`（与 `first()` 一致）；只有「多于一行」才抛
    /// `IllegalStateException`。
    @Test
    void singleReturnsNullWhenNothingMatches() {
        T single = query().where(nameRef()).eq(MISSING_NAME).single();

        assertThat(single).isNull();
    }

    /// `exists()` 无行时是 `false`。
    @Test
    void existsFalseWhenNothingMatches() {
        boolean exists = query().where(nameRef()).eq(MISSING_NAME).exists();

        assertThat(exists).isFalse();
    }
}
