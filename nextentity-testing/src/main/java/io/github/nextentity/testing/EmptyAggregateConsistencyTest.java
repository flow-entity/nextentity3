package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.api.model.Tuple2;
import java.util.List;
import org.junit.jupiter.api.Test;

/// 零行与「有行但值全为 `null`」上的聚合语义。
///
/// 这两件事必须分开钉：SQL 里零行的 `COUNT` 是 `0`、`SUM`/`MAX`/`MIN`/`AVG` 是 `NULL`，而聚合查询
/// **仍返回一行**；「有一行、但被聚合的列是 `NULL`」则是 `COUNT(该列) = 0` 而 `COUNT(主键) = 1`、
/// `SUM`/`AVG` 同样 `NULL`。三种错误实现都能骗过只看 `size()` 或只看 `total` 的用例：
/// 把「无行」当成「没有结果行」返回空列表、把 `null` 兜成 `0`、把 `0` 兜成 `null`。
///
/// 零行上的**终止符**（`list` / `count` / `first` / `single` / `exists`）见
/// {@link EmptyTerminalConsistencyTest}。
///
/// @param <T> 后端实体类型
public abstract class EmptyAggregateConsistencyTest<T extends Item> extends ConsistencyTestBase<T> {

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

    /// 「有行但聚合列全为 `null`」不等于「无行」：`count(主键) = 1`、`count(score) = 0`、
    /// `sum(score) = null`。夹具里 `apple pie` 那一行 `score` 就是 `null`。
    ///
    /// 把这两种情形混为一谈的实现（例如用 `sum == null` 反推「没查到数据」）在消费方看来是
    /// 「有数据但和为 0」还是「没数据」，判断会整体错位。
    @Test
    void aggregatesOnRowsWhoseColumnIsAllNull() {
        List<Tuple2<Long, Integer>> row = query().select(
                        Path.of(idRef()).count(), Path.of(scoreRef()).sum())
                .where(nameRef())
                .eq(APPLE_PIE_NAME)
                .list();

        assertThat(row).hasSize(1);
        assertThat(row.getFirst().get0()).isEqualTo(1L);
        assertThat(row.getFirst().get1()).isNull();

        // count(被聚合列) 走的是 COUNT(列) 而不是 COUNT(*)：全 null 的列必须是 0。
        // 只断 count(主键) = 1 的用例看不出实现把两者写反。
        List<Tuple2<Long, Integer>> columnCount = query().select(
                        Path.of(scoreRef()).count(), Path.of(scoreRef()).sum())
                .where(nameRef())
                .eq(APPLE_PIE_NAME)
                .list();

        assertThat(columnCount).hasSize(1);
        assertThat(columnCount.getFirst().get0()).isZero();
        assertThat(columnCount.getFirst().get1()).isNull();
    }

    /// `groupBy` 无行时是**零行**，与「无分组聚合返回一行」正好相反：分组的结果集行数跟着组数走，
    /// 没有组就没有行。
    @Test
    void groupByReturnsNoRowsWhenNothingMatches() {
        List<Tuple2<String, Long>> rows = query().select(
                        Path.of(noteRef()), Path.of(idRef()).count())
                .where(nameRef())
                .eq(MISSING_NAME)
                .groupBy(noteRef())
                .list();

        assertThat(rows).isEmpty();
    }
}
