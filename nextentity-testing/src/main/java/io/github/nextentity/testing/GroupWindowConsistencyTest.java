package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.BaseWhereStep;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.model.Slice;
import io.github.nextentity.api.model.Tuple2;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `groupBy` 之后的**分页窗口**：`SliceStep#slice(offset, limit)` 与 `Collector#list(offset, limit)`
/// 两条入口共用一套口径——offset/limit 作用在组上、total 是组数、`limit=0` 不限行、负数抛 IAE。
///
/// 与 [`GroupCountConsistencyTest`] 分开：那边数「组数对不对」，这边数「组对不对、窗口有没有下推到
/// 组上」；混在一起时一个方言窗口差异会把两族的红点搅成一处。
///
/// @param <T> 后端实体类型
public abstract class GroupWindowConsistencyTest<T extends Item> extends ConsistencyTestBase<T> {

    @Test
    @DisplayName("窗口作用在组上：total 是组数、页大小按组生效")
    void windowAppliesToGroups() {
        // 分组键必须进 select：整实体投影 + groupBy 不是合法 SQL（列不在 GROUP BY 里）
        Slice<?> slice = query().select(Path.of(statusRef()), Path.of(idRef()).count())
                .groupBy(statusRef())
                .orderBy(statusRef())
                .asc()
                .slice(0, 2);

        assertThat(slice.total()).isEqualTo(expectedGroupCount());
        assertThat(slice.data()).hasSize((int) Math.min(2L, expectedGroupCount()));
    }

    @Test
    @DisplayName("slice(offset,limit)：total 仍是组数，offset 作用在组上")
    void sliceWithOffsetStillCountsGroups() {
        List<ItemStatus> statuses = orderedStatuses();

        Slice<Tuple2<ItemStatus, Long>> page = groupedWindow().slice(1, 1);

        assertThat(page.total()).as("total 数的是组数，不是行数，也与 offset 无关").isEqualTo(expectedGroupCount());
        assertThat(page.data()).as("limit 作用在组上").hasSize(1);
        assertThat(page.data().getFirst().get0()).as("offset=1 跳过第一组").isEqualTo(statuses.get(1));
    }

    @Test
    @DisplayName("offset 越过组数：total 仍是组数、页为空")
    void offsetBeyondGroupCountYieldsEmptyPage() {
        Slice<Tuple2<ItemStatus, Long>> page = groupedWindow().slice((int) expectedGroupCount(), 5);

        assertThat(page.total()).as("越过末页也要给出总组数").isEqualTo(expectedGroupCount());
        assertThat(page.data()).isEmpty();
    }

    @Test
    @DisplayName("Collector.list(offset,limit)：窗口作用在组上，limit=0 表示不限行")
    void collectorWindowAppliesToGroups() {
        List<ItemStatus> statuses = orderedStatuses();

        List<Tuple2<ItemStatus, Long>> second = groupedWindow().list(1, 1);
        assertThat(second).hasSize(1);
        assertThat(second.getFirst().get0()).isEqualTo(statuses.get(1));

        // limit=0 是不限行，不是「取 0 行」——两者在分页语义里天差地别
        List<Tuple2<ItemStatus, Long>> rest = groupedWindow().list(1, 0);
        assertThat(rest).extracting(Tuple2::get0).containsExactlyElementsOf(statuses.subList(1, statuses.size()));
    }

    @Test
    @DisplayName("Collector.list(offset,limit)：负数窗口显式拒绝（IAE，不静默夹断）")
    void collectorWindowRejectsNegativeArguments() {
        assertThatThrownBy(() -> groupedWindow().list(-1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> groupedWindow().list(0, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    /// 分组窗口查询：分组键与组内计数都要进 `select`（整实体投影 + `groupBy` 不是合法 SQL），
    /// 显式 `orderBy(status)` 让「第几组」在两端可预期。
    ///
    /// @return 已定序的分组查询，可接 `slice(...)` / `list(...)`
    private BaseWhereStep<T, Tuple2<ItemStatus, Long>> groupedWindow() {
        return query().select(Path.of(statusRef()), Path.of(idRef()).count())
                .groupBy(statusRef())
                .orderBy(statusRef())
                .asc();
    }

    /// 内存 oracle：夹具里出现过的状态，按升序去重（与 `orderBy(status).asc()` 同序）。
    ///
    /// 排序键是**枚举名**不是枚举声明序：`status` 列是 `@Enumerated(STRING)`，SQL 的
    /// `order by status` 按磁盘上的字符串比较（`ACTIVE` < `DONE` < `NEW`），与
    /// `Enum#ordinal`（NEW < ACTIVE < DONE）不是一回事。按 `ordinal` 排会造出一个
    /// 后端不可能满足的 oracle——那是测试自己的 bug，不是引擎的。
    ///
    /// @return 有序去重状态列表
    private List<ItemStatus> orderedStatuses() {
        return fixtureRows().stream()
                .map(FixtureRow::status)
                .distinct()
                .sorted(Comparator.comparing(Enum::name))
                .toList();
    }

    private long expectedGroupCount() {
        return fixtureRows().stream().map(FixtureRow::status).distinct().count();
    }
}
