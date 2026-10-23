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

/// 显式投影映射器的**下发语句**断言：只证明 `expressions()` 真的变成了 SQL 的投影列，不锁死整条语句文本。
///
/// 一致性用例（`ProjectionMapperConsistencyTest`）证明结果集对；本类补另外一半证据——
/// 列序在渲染出口与 `expressions()` 一致、聚合落成聚合函数。断言一律 `containsIgnoringCase`
/// 或只比较投影段内的相对位置，避免把方言的引号、别名、表前缀写进期望值。
class ItemProjectionMapperRenderingTest {

    private final JpaItemSupport support = new JpaItemSupport("item_projection_mapper_rendering");

    @AfterEach
    void closeSupport() {
        support.close();
    }

    /// 三列映射器的投影子句按 `expressions()` 的顺序出现，聚合列渲染成聚合函数。
    ///
    /// 列序断言只比较投影段内的出现位置（`note` < `status` < `count(`）：这是"渲染顺序 = 声明顺序"
    /// 在不锁死别名与表前缀的前提下能做到的最强断言；列序若颠倒，三段位置关系立刻失败。
    @Test
    void projectionColumnsAppearInExpressionOrder() {
        support.clearSql();

        support.query()
                .project(new NoteStatusCountMapper())
                .groupBy(support.noteRef())
                .groupBy(support.statusRef())
                .list();

        String projection = projectionClause(support.drainSql());
        int noteAt = projection.toLowerCase(Locale.ROOT).indexOf("note");
        int statusAt = projection.toLowerCase(Locale.ROOT).indexOf("status");
        int countAt = projection.toLowerCase(Locale.ROOT).indexOf("count(");

        assertThat(noteAt).as("投影段里必须有 note 列：%s", projection).isNotNegative();
        assertThat(statusAt).as("note 必须排在 status 之前：%s", projection).isGreaterThan(noteAt);
        assertThat(countAt).as("聚合列必须排在最后且渲染成 count(...)：%s", projection).isGreaterThan(statusAt);
    }

    /// 单列映射器（一个 `sum`）同样下发到 SQL，而不是在应用侧聚合。
    @Test
    void sumIsRenderedAsAggregate() {
        support.clearSql();

        support.query().project(new ScoreSumMapper()).list();

        assertThat(support.drainSql()).anySatisfy(sql -> assertThat(sql).containsIgnoringCase("sum("));
    }

    /// 取第一条语句的投影段（`select` 与紧随其后的 ` from ` 之间）。
    ///
    /// 只解析这一段：整条语句的其余部分（表别名、`group by`、参数占位）都随方言变化，不参与断言。
    private static String projectionClause(List<String> sqls) {
        for (String sql : sqls) {
            String lower = sql.toLowerCase(Locale.ROOT);
            int selectAt = lower.indexOf("select");
            int fromAt = lower.indexOf(" from ", selectAt + "select".length());
            if (selectAt >= 0 && fromAt > selectAt) {
                return sql.substring(selectAt + "select".length(), fromAt);
            }
        }
        throw new AssertionError("没有抓到带投影子句的语句：" + sqls);
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
