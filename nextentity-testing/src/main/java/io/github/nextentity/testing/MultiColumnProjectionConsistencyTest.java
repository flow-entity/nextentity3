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
    @Test
    void fourColumnsKeepOrderAndTypes() {
        List<Tuple4<Long, String, Integer, ItemStatus>> rows = query().project(ProjectionMappers.select(
                        Path.of(idRef()), Path.of(nameRef()), Path.of(scoreRef()), Path.of(statusRef())))
                .orderBy(idRef())
                .asc()
                .list();

        assertThat(rows).hasSize(4);
        assertThat(rows.getFirst().size()).isEqualTo(4);
        assertThat(rows.getFirst().get0()).isEqualTo(APPLE_ID);
        assertThat(rows.getFirst().get1()).isEqualTo(APPLE_NAME);
        assertThat(rows.getFirst().get2()).isEqualTo(10);
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
    @Test
    void andThenMapsToCallerType() {
        record Row(Long id, String name) {}

        List<Row> rows = query().project(ProjectionMappers.select(
                                Path.of(idRef()), Path.of(nameRef()), Path.of(scoreRef()), Path.of(statusRef()))
                        .andThen(tuple -> new Row(tuple.get0(), tuple.get1())))
                .orderBy(idRef())
                .asc()
                .list();

        assertThat(rows).hasSize(4);
        assertThat(rows.getFirst()).isEqualTo(new Row(APPLE_ID, APPLE_NAME));
        assertThat(rows.getLast()).isEqualTo(new Row(APPLE_PIE_ID, APPLE_PIE_NAME));
    }
}
