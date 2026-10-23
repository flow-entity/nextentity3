package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.api.model.Tuple;
import io.github.nextentity.api.model.Tuple2;
import java.util.List;
import org.junit.jupiter.api.Test;

/// 分组后过滤（`having`）与多列分组的一致性。
///
/// 夹具按 `note` 分组：`null` → 2 行（id 1、4），`n1` → 1 行（id 2），`n2` → 1 行（id 3）。
/// 按 `note` + `status` 分两列：`(null,NEW)` → 2 行、`(n1,ACTIVE)` → 1 行、`(n2,DONE)` → 1 行。
///
/// `having` 作用于**分组后**的聚合值（`count` / `max` …），与 `where` 作用于分组前的行不同：
/// 同一个 `count(id) > 1` 写在 `where` 里没有意义（那时还没有分组），写在 `having` 里才是「留下计数大于 1 的组」。
///
/// @param <T> 后端实体类型
public abstract class HavingConsistencyTest<T extends Item> extends ConsistencyTestBase<T> {

    /// `having(count(id) > 1)` 只留下计数为 2 的空 note 组。
    @Test
    void havingCountKeepsOnlyGroupsWithMoreThanOneRow() {
        List<Tuple2<String, Long>> rows = query().select(
                        Path.of(noteRef()), Path.of(idRef()).count())
                .groupBy(noteRef())
                .having(Path.of(idRef()).count().gt(1L))
                .list();

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get0()).isNull();
        assertThat(rows.getFirst().get1()).isEqualTo(2L);
    }

    /// `having(max(score) >= 30)` 只留下最大值为 30 的 n2 组。
    @Test
    void havingMaxKeepsGroupWithLargestValue() {
        List<Tuple2<String, Integer>> rows = query().select(
                        Path.of(noteRef()), Path.of(scoreRef()).max())
                .groupBy(noteRef())
                .having(Path.of(scoreRef()).max().ge(30))
                .list();

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get0()).isEqualTo(NOTE_2);
        assertThat(rows.getFirst().get1()).isEqualTo(30);
    }

    /// `having` 之后可以继续 `orderBy`：按 note 升序时非空部分依次是 n1、n2（NULL 的位置由数据库决定）。
    @Test
    void havingCanBeFollowedByOrderBy() {
        List<Tuple2<String, Long>> rows = query().select(
                        Path.of(noteRef()), Path.of(idRef()).count())
                .groupBy(noteRef())
                .having(Path.of(idRef()).count().ge(1L))
                .orderBy(Path.of(noteRef()))
                .asc()
                .list();

        assertThat(rows).hasSize(3);
        assertThat(rows.stream().map(Tuple2::get0).filter(note -> note != null).toList())
                .containsExactly(NOTE_1, NOTE_2);
    }

    /// 不带 `groupBy` 的 `having` = 全表聚合后过滤：4 行满足 `count > 3`，返回单个值。
    @Test
    void havingWithoutGroupByFiltersWholeTableAggregate() {
        List<Long> kept = query().select(Path.of(idRef()).count())
                .having(Path.of(idRef()).count().gt(3L))
                .list();

        assertThat(kept).containsExactly(4L);
    }

    /// 全表聚合被 `having` 拒绝时返回空列表（不是返回 `0`，也不是返回一行 `null`）。
    @Test
    void havingWithoutGroupByRejectsWholeTableAggregate() {
        List<Long> kept = query().select(Path.of(idRef()).count())
                .having(Path.of(idRef()).count().gt(4L))
                .list();

        assertThat(kept).isEmpty();
    }

    /// 两列分组 + 三列投影：`(note, status, count(id))` 恰好三组。
    @Test
    void multiColumnGroupByProducesOneRowPerGroup() {
        List<Tuple> rows = query().select(
                        Path.of(noteRef()),
                        Path.of(statusRef()),
                        Path.of(idRef()).count())
                .groupBy(noteRef())
                .groupBy(statusRef())
                .list();

        assertThat(rows).hasSize(3);
        assertThat(rows.stream()
                        .map(row -> row.get(0) + "|" + row.get(1) + "|" + row.get(2))
                        .toList())
                .containsExactlyInAnyOrder("null|NEW|2", NOTE_1 + "|ACTIVE|1", NOTE_2 + "|DONE|1");
    }
}
