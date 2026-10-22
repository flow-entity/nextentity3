package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.api.model.Tuple2;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/// 投影与聚合一致性：单列、双列 `Tuple2`、`count` / `countDistinct` / `max` / `sum`。
///
/// point-mall 的投影只有 `select(Path...)` 一种形态（`select(X.class)` 调用数为 0），所以这里不测
/// DTO / 接口投影。
///
/// @param <T> 后端实体类型
public abstract class ProjectionConsistencyTest<T extends Item> extends ConsistencyTestBase<T> {

    /// 单列投影按排序返回该列的值。
    @Test
    void selectSingleColumnProjectsValues() {
        List<String> names = query().select(nameRef()).orderBy(idRef()).asc().list();

        assertThat(names).containsExactly(APPLE_NAME, BANANA_NAME, CHERRY_NAME, APPLE_PIE_NAME);
    }

    /// 双列投影返回 `Tuple2`，列序不得颠倒。
    @Test
    void selectTwoColumnsReturnsTuple2() {
        List<Tuple2<Long, String>> rows = query().select(Path.of(idRef()), Path.of(nameRef()))
                .orderBy(idRef())
                .asc()
                .list();

        assertThat(rows).extracting(Tuple2::get0).containsExactly(APPLE_ID, BANANA_ID, CHERRY_ID, APPLE_PIE_ID);
        assertThat(rows).extracting(Tuple2::get1).containsExactly(APPLE_NAME, BANANA_NAME, CHERRY_NAME, APPLE_PIE_NAME);
    }

    /// `count()` 聚合：无分组时返回一行。
    @Test
    void countAggregateReturnsOneRow() {
        List<Long> counts = query().select(Path.of(idRef()).count()).list();

        assertThat(counts).containsExactly(4L);
    }

    /// `countDistinct()` 不把 `null` 计入。
    @Test
    void countDistinctIgnoresNull() {
        List<Long> counts = query().select(Path.of(scoreRef()).countDistinct()).list();

        assertThat(counts).containsExactly(3L);
    }

    /// `max()` 聚合。
    @Test
    void maxAggregate() {
        List<Integer> max = query().select(Path.of(scoreRef()).max()).list();

        assertThat(max).containsExactly(30);
    }

    /// `sum()` 聚合，且聚合作用于过滤后的行。
    @Test
    void sumAggregateAppliesToFilteredRows() {
        List<Integer> sum = query().select(Path.of(scoreRef()).sum())
                .where(scoreRef())
                .ge(20)
                .list();

        assertThat(sum).containsExactly(50);
    }

    /// `groupBy` + 聚合：point-mall 的 6 处分组查询都是 `select(key, count) … groupBy(key)` 这个形状，
    /// 返回 `List<Tuple2<K, Long>>`，空分组值为 `null`（消费方按 `get1() != null ? get1() : 0L` 兜）。
    @Test
    void groupByWithCountAggregate() {
        List<Tuple2<String, Long>> rows = query().select(
                        Path.of(noteRef()), Path.of(noteRef()).count())
                .groupBy(noteRef())
                .list();

        assertThat(rows).hasSize(3);
        assertThat(rows.stream()
                        .filter(row -> row.get0() != null)
                        .collect(Collectors.toMap(Tuple2::get0, Tuple2::get1)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(NOTE_1, 1L, NOTE_2, 1L));
        assertThat(rows)
                .filteredOn(row -> row.get0() == null)
                .extracting(Tuple2::get1)
                .containsExactly(2L);
    }
}
