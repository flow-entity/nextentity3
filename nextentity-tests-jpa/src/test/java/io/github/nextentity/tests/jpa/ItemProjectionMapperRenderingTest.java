package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Expression;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.ProjectionMapper;
import io.github.nextentity.api.model.Tuple;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// 显式投影映射器的**下发语句**断言：整句快照，钉住 `expressions()` 到 SQL 投影列的完整映射。
///
/// 一致性用例（`ProjectionMapperConsistencyTest`）证明结果集对；本类补另外一半证据——
/// 列序与别名在渲染出口与 `expressions()` 完全一致、聚合落成聚合函数而不是应用侧聚合。
///
/// 断言是**整句快照**（转小写 + 连续空白压一个空格后 `isEqualTo`），不是 `containsIgnoringCase`
/// 片段：片段断言对「列序颠倒」「别名错位」「聚合被挪到应用侧」这类偏差都可能假绿——只要语句里
/// 还残留 `sum(` 字样就通过，而那正是本类要抓的语义。
///
/// 快照只对 **H2 + 当前 Hibernate** 成立（表别名 `ie1_0`、列别名 `c0..c2` 都来自 Hibernate 渲染），
/// 因此本类不进 MySQL 通道，与 `ItemSqlSnapshotTest` 同一口径。
class ItemProjectionMapperRenderingTest {

    private final JpaItemSupport support = new JpaItemSupport("item_projection_mapper_rendering");

    @AfterEach
    void closeSupport() {
        support.close();
    }

    /// 三列映射器的投影子句按 `expressions()` 的顺序渲染成 `c0`/`c1`/`c2`，聚合列渲染成 `count(...)`。
    ///
    /// 整句比对同时钉住：投影列顺序（`note` 在 `status` 前、聚合列最后）、列别名按声明顺序编号、
    /// `group by` 复用同一批别名（`group by c0,c1`）。列序颠倒、别名编号错位、`group by` 改回
    /// 重复表达式，这条都会红。
    @Test
    void projectionColumnsAppearInExpressionOrder() {
        support.clearSql();

        support.query()
                .project(new NoteStatusCountMapper())
                .groupBy(support.noteRef())
                .groupBy(support.statusRef())
                .list();

        assertThat(normalized(support.drainSql()))
                .containsExactly(
                        "select ie1_0.note c0,ie1_0.status c1,count(ie1_0.id) c2 from item ie1_0 group by c0,c1");
    }

    /// 单列映射器（一个 `sum`）下发成 `select sum(ie1_0.score) from item ie1_0`，而不是在应用侧聚合。
    ///
    /// 整句比对钉住「聚合发生在数据库」：把 `sum` 退化成取列、或多套一层 `cast`/`distinct`、
    /// 或改成应用侧聚合后下发裸列查询，都会红；只断 `containsIgnoringCase("sum(")` 时，
    /// 语句里任何位置残留 `sum(` 都能蒙过去。
    @Test
    void sumIsRenderedAsAggregate() {
        support.clearSql();

        support.query().project(new ScoreSumMapper()).list();

        assertThat(normalized(support.drainSql())).containsExactly("select sum(ie1_0.score) from item ie1_0");
    }

    /// 与 `ItemSqlSnapshotTest` 同一套归一化：转小写 + 连续空白压一个空格，其余字符原样比对。
    private static List<String> normalized(List<String> sqls) {
        return sqls.stream()
                .map(sql -> sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim())
                .toList();
    }

    /// 三列（属性 + 属性 + 聚合）：钉住「列序在渲染出口与 `expressions()` 一致」。
    private static final class NoteStatusCountMapper implements ProjectionMapper<ItemEntity, Object> {

        @Override
        public List<Expression<ItemEntity, ?>> expressions() {
            return List.of(
                    Path.of(ItemEntity::note),
                    Path.of(ItemEntity::status),
                    Path.of(ItemEntity::id).count());
        }

        @Override
        public Object map(Tuple row) {
            return row;
        }
    }

    /// 单列（`sum(score)`）：钉住聚合真的渲染成聚合函数。
    private static final class ScoreSumMapper implements ProjectionMapper<ItemEntity, Object> {

        @Override
        public List<Expression<ItemEntity, ?>> expressions() {
            return List.of(Path.of(ItemEntity::score).sum());
        }

        @Override
        public Object map(Tuple row) {
            return row;
        }
    }
}
