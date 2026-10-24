package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.api.ProjectionMappers;
import io.github.nextentity.api.model.Tuple4;
import io.github.nextentity.api.model.Tuple5;
import java.util.List;
import org.junit.jupiter.api.Test;

/// 四列及以上投影的一致性：由 `ProjectionMappers` 产出的映射器把多列装配成具名元组。
///
/// 三列及以下走 `select(...)`（见 `ProjectionConsistencyTest`），本类只覆盖"必须走映射器"的那一档，
/// 顺带钉住两件事：列序即实参顺序；具名访问器给出的类型与夹具字段类型一致。
///
/// @param <T> 后端实体类型
public abstract class MultiColumnProjectionConsistencyTest<T extends Item> extends ConsistencyTestBase<T> {

    /// 四列：列序、`size()` 与各具名访问器对位；末行的 `score` 是 `null`（夹具 id=4）。
    ///
    /// 四行**逐行**钉住（`orderBy(id)` 让顺序确定）：只断 `hasSize(4)` + 首行全字段 + 末行 `score`
    /// 为 `null` 时，中间两行整行错掉（列错位、`score` 与 `score2` 互换、行被重复）都不会红。
    @Test
    void fourColumnsKeepOrderAndTypes() {
        List<Tuple4<Long, String, Integer, ItemStatus>> rows = query().project(ProjectionMappers.select(
                        Path.of(idRef()), Path.of(nameRef()), Path.of(scoreRef()), Path.of(statusRef())))
                .orderBy(idRef())
                .asc()
                .list();

        assertThat(rows)
                .containsExactly(
                        new Tuple4<>(APPLE_ID, APPLE_NAME, 10, ItemStatus.NEW),
                        new Tuple4<>(BANANA_ID, BANANA_NAME, 20, ItemStatus.ACTIVE),
                        new Tuple4<>(CHERRY_ID, CHERRY_NAME, 30, ItemStatus.DONE),
                        new Tuple4<>(APPLE_PIE_ID, APPLE_PIE_NAME, null, ItemStatus.NEW));
        assertThat(rows.getFirst().size()).isEqualTo(4);
        assertThat(rows.getFirst().get3()).isEqualTo(ItemStatus.NEW);
        assertThat(rows.getLast().get2()).isNull();
    }

    /// 五列：列数加宽后末列仍对位；`null` 与重复列值不改变列序。
    @Test
    void fiveColumnsKeepLastColumnAligned() {
        List<Tuple5<Long, String, Integer, Integer, String>> rows = query().project(ProjectionMappers.select(
                        Path.of(idRef()),
                        Path.of(nameRef()),
                        Path.of(scoreRef()),
                        Path.of(score2Ref()),
                        Path.of(noteRef())))
                .where(idRef())
                .eq(BANANA_ID)
                .list();

        assertThat(rows).hasSize(1);
        Tuple5<Long, String, Integer, Integer, String> row = rows.getFirst();
        assertThat(row.size()).isEqualTo(5);
        assertThat(row.get0()).isEqualTo(BANANA_ID);
        assertThat(row.get1()).isEqualTo(BANANA_NAME);
        assertThat(row.get2()).isEqualTo(20);
        assertThat(row.get3()).isEqualTo(10);
        assertThat(row.get4()).isEqualTo(NOTE_1);
    }

    /// `andThen`：列集不变，结果换成调用方类型；类型由 lambda 的返回值推断。
    ///
    /// 四行逐行钉住映射结果（`orderBy(id)` 让顺序确定）：原先只断 `hasSize(4)` + 首末两行时，
    /// 中间两行的 `id`/`name` 配错（例如错位取 `get0`/`get2`）不会被发现；`andThen` 的入参
    /// 是 4 列元组，取错列恰恰是这个 API 最容易犯的错。
    @Test
    void andThenMapsToCallerType() {
        record Row(Long id, String name) {}

        List<Row> rows = query().project(ProjectionMappers.select(
                                Path.of(idRef()), Path.of(nameRef()), Path.of(scoreRef()), Path.of(statusRef()))
                        .andThen(tuple -> new Row(tuple.get0(), tuple.get1())))
                .orderBy(idRef())
                .asc()
                .list();

        assertThat(rows)
                .containsExactly(
                        new Row(APPLE_ID, APPLE_NAME),
                        new Row(BANANA_ID, BANANA_NAME),
                        new Row(CHERRY_ID, CHERRY_NAME),
                        new Row(APPLE_PIE_ID, APPLE_PIE_NAME));
    }
}
