package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.api.model.Tuple2;
import io.github.nextentity.api.model.Tuple3;
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

    /// `distinct()` 单列去重：point-mall 的调用点都是单列投影 + `list()`
    /// （`DigitalCardRepository:85`、`OrderImportRecordRepository:71`）。
    ///
    /// `DISTINCT` 把 `NULL` 当作一个分组（SQL 标准，H2/MySQL 一致），所以夹具的两个空 `note`
    /// 只会出现一次——这既是去重的证据，也解释了消费方为什么总在非空列上去重。
    ///
    /// 结果整体钉住 `{n1, n2, null}`，不是「行数 + 非空子集」：后者在「一个 `null` 被换成重复的
    /// `n1`」时行数与非空集合都成立，而 `DISTINCT` 对 `NULL` 的归并恰恰是这条用例要证明的语义。
    @Test
    void distinctCollapsesDuplicateValues() {
        List<String> notes = query().distinct().select(noteRef()).list();

        assertThat(notes).containsExactlyInAnyOrder(NOTE_1, NOTE_2, null);
    }

    /// `groupBy` + 聚合：point-mall 的 6 处分组查询都是 `select(key, count) … groupBy(key)` 这个形状，
    /// 返回 `List<Tuple2<K, Long>>`，空分组值为 `null`（消费方按 `get1() != null ? get1() : 0L` 兜）。
    ///
    /// 计数投影用主键而不是分组列：`count(分组列)` 按 SQL 语义不数 `NULL`（空分组会得 0），
    /// 消费方要的是「该组实体行数」。
    @Test
    void groupByWithCountAggregate() {
        List<Tuple2<String, Long>> rows = query().select(
                        Path.of(noteRef()), Path.of(idRef()).count())
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

    /// `min()` 聚合：忽略 `null`，取最小非空值。
    @Test
    void minAggregateIgnoresNull() {
        List<Integer> min = query().select(Path.of(scoreRef()).min()).list();

        assertThat(min).containsExactly(10);
    }

    /// `avg()` 聚合：返回 `Double`，分母是**非空**值的个数（10+20+30 除以 3）。
    @Test
    void avgAggregateIgnoresNullAndReturnsDouble() {
        List<Double> avg = query().select(Path.of(scoreRef()).avg()).list();

        assertThat(avg).containsExactly(20.0);
    }

    /// 三列投影返回 `Tuple3`：具名访问器带类型，按位置取值与之一致。
    ///
    /// 四行**逐行**钉住完整投影值（`orderBy(id)` 让顺序确定）：原先只断 `hasSize(4)` +
    /// 首行/末行一个字段时，中间两行整行错掉（列错位、`score` 与 `score2` 互换、行被重复）
    /// 都不会红。列错位正是多列投影最容易出的偏差，必须逐行钉住。
    @Test
    void selectThreeColumnsReturnsTuple() {
        List<Tuple3<Long, String, Integer>> rows = query().select(
                        Path.of(idRef()), Path.of(nameRef()), Path.of(scoreRef()))
                .orderBy(idRef())
                .asc()
                .list();

        assertThat(rows)
                .containsExactly(
                        new Tuple3<>(APPLE_ID, APPLE_NAME, 10),
                        new Tuple3<>(BANANA_ID, BANANA_NAME, 20),
                        new Tuple3<>(CHERRY_ID, CHERRY_NAME, 30),
                        new Tuple3<>(APPLE_PIE_ID, APPLE_PIE_NAME, null));
        assertThat(rows.getFirst().size()).isEqualTo(3);
        assertThat(rows.getFirst().get(2)).isEqualTo(rows.getFirst().get2());
        assertThat(rows.getFirst().get(0)).isEqualTo(APPLE_ID);
        assertThat(rows.getLast().get(2)).isNull();
    }

    /// 两列投影仍是 `Tuple2`，但也能按位置 `get(int)`（`Tuple2 extends Tuple`）。
    @Test
    void tuple2AlsoSupportsPositionalAccess() {
        List<Tuple2<Long, String>> rows = query().select(Path.of(idRef()), Path.of(nameRef()))
                .orderBy(idRef())
                .asc()
                .list();

        assertThat(rows.getFirst().size()).isEqualTo(2);
        assertThat(rows.getFirst().get(0)).isEqualTo(APPLE_ID);
        assertThat(rows.getFirst().get(1)).isEqualTo(APPLE_NAME);
        assertThat(rows.getFirst().get(1)).isEqualTo(rows.getFirst().get1());
    }
}
