package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/// 无行时的聚合与列表语义：零行上的 `count` / `countDistinct` / `sum` / `max` / `min` / `avg` 与 `list`。
///
/// 这一组钉的是**空集合语义**，与「有行但值全为 `null`」是两件事：SQL 里 `COUNT` 在零行上返回 `0`、
/// `SUM`/`MAX`/`MIN`/`AVG` 返回 `NULL`，而且聚合查询本身**仍返回一行**。三种错误实现都能骗过只看
/// `total`/`size` 的用例：把「无行」当成「没有结果行」而返回空列表、把 `null` 兜成 `0`、
/// 或者反过来把 `0` 兜成 `null`——消费方 `sum != null ? sum : 0` / `get1() != null ? get1() : 0L`
/// 这类判断会因此错位。
///
/// @param <T> 后端实体类型
public abstract class EmptyResultConsistencyTest<T extends Item> extends ConsistencyTestBase<T> {

    /// `count()` 无行时返回**一行** `0`，既不是空列表也不是 `null`（`Aggregates#count` 的公开契约）。
    @Test
    void countAggregateReturnsOneZeroRowWhenNoRows() {
        List<Long> counts = query().select(Path.of(idRef()).count())
                .where(nameRef())
                .eq(MISSING_NAME)
                .list();

        assertThat(counts).containsExactly(0L);
    }

    /// `countDistinct()` 无行时同样是**一行** `0`。
    @Test
    void countDistinctAggregateReturnsZeroWhenNoRows() {
        List<Long> counts = query().select(Path.of(scoreRef()).countDistinct())
                .where(nameRef())
                .eq(MISSING_NAME)
                .list();

        assertThat(counts).containsExactly(0L);
    }

    /// `sum()` 无行时是一行 `null`，不是 `0`（见 `NumberPath#sum`）。
    @Test
    void sumAggregateReturnsNullWhenNoRows() {
        List<Integer> sums = query().select(Path.of(scoreRef()).sum())
                .where(nameRef())
                .eq(MISSING_NAME)
                .list();

        assertThat(sums).hasSize(1);
        assertThat(sums.getFirst()).isNull();
    }

    /// `max()` 无行时是一行 `null`（见 `Aggregates#max`）。
    @Test
    void maxAggregateReturnsNullWhenNoRows() {
        List<Integer> max = query().select(Path.of(scoreRef()).max())
                .where(nameRef())
                .eq(MISSING_NAME)
                .list();

        assertThat(max).hasSize(1);
        assertThat(max.getFirst()).isNull();
    }

    /// `min()` 无行时是一行 `null`（见 `Aggregates#min`）。
    @Test
    void minAggregateReturnsNullWhenNoRows() {
        List<Integer> min = query().select(Path.of(scoreRef()).min())
                .where(nameRef())
                .eq(MISSING_NAME)
                .list();

        assertThat(min).hasSize(1);
        assertThat(min.getFirst()).isNull();
    }

    /// `avg()` 无行时是一行 `null`，不是 `0.0`（见 `NumberPath#avg`）。
    @Test
    void avgAggregateReturnsNullWhenNoRows() {
        List<Double> avg = query().select(Path.of(scoreRef()).avg())
                .where(nameRef())
                .eq(MISSING_NAME)
                .list();

        assertThat(avg).hasSize(1);
        assertThat(avg.getFirst()).isNull();
    }

    /// `list()` 无行时是空列表（对比：同一条件下的 `count()` 是一行 `0`）。
    @Test
    void listReturnsEmptyWhenNoRows() {
        List<T> rows = query().where(nameRef()).eq(MISSING_NAME).list();

        assertThat(rows).isEmpty();
    }
}
