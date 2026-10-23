package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.Expression;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.ProjectionMapper;
import io.github.nextentity.api.model.Tuple;
import java.util.List;
import org.junit.jupiter.api.Test;

/// 显式投影映射器（`SelectStep#project`）的一致性：列序、单列成元组、聚合无分组恒 1 行、映射器可复用、
/// 聚合查询的终止符护栏。
///
/// 与 `core/builder/ProjectionMapperTest`（假后端，只钉规格形状）互补：这里用真实 SQL 证明结果集。
/// 夹具见 [ConsistencyTestBase]，期望值一律按那 4 行手算。
///
/// @param <T> 后端实体类型
public abstract class ProjectionMapperConsistencyTest<T extends Item> extends ConsistencyTestBase<T> {

    /// 端到端：`project` 与 `where` / `groupBy` / `orderBy` / `list()` 组合，列序即 `expressions()` 的顺序。
    ///
    /// 计数取主键而不是分组列：SQL 的 `count(列)` 不数 `NULL`，而消费方要的是组内行数。
    /// `where` 取主键闭区间是为了保留全部夹具行，让期望值能直接按夹具手算，同时证明 WHERE 与映射器路径可组合。
    /// 排序断言依赖 `@Enumerated(STRING)` 的字典序（`ACTIVE < DONE < NEW`），前提与 [EnumConsistencyTest] 相同。
    @Test
    void projectMapsGroupedRowsInExpressionOrder() {
        List<StatusCount> rows = query().project(new StatusCountMapper())
                .where(idRef())
                .between(APPLE_ID, APPLE_PIE_ID)
                .groupBy(statusRef())
                .orderBy(statusRef())
                .asc()
                .list();

        assertThat(rows)
                .containsExactly(
                        new StatusCount(ItemStatus.ACTIVE, 1L, 2),
                        new StatusCount(ItemStatus.DONE, 1L, 2),
                        new StatusCount(ItemStatus.NEW, 2L, 2));
    }

    /// 单列映射器：`map` 收到的仍是长度为 1 的 `Tuple`，不是裸值。
    ///
    /// `sum` 按 SQL 语义忽略 `NULL`：夹具里 id=4 的 `score` 为空，总和是 10+20+30。
    @Test
    void singleColumnMapperReceivesOneColumnTuple() {
        List<ScoreSum> sums = query().project(new ScoreSumMapper()).list();

        assertThat(sums).containsExactly(new ScoreSum(60, 1));
    }

    /// 聚合投影 + 无 `groupBy`：SQL 把整表当一个分组，因此恰好 1 行。
    ///
    /// 这是 [ProjectionMapper] 类注释里那条告警的可执行证据：漏写 `groupBy` 不报错，只会拿到一行总数。
    @Test
    void aggregateWithoutGroupByReturnsSingleRow() {
        List<SumAndCount> rows = query().project(new ScoreSumAndCountMapper()).list();

        assertThat(rows).containsExactly(new SumAndCount(60, 4L, 2));
    }

    /// 空表上聚合无 `groupBy` 仍是 1 行：`count` 给 0，`sum` 给 `null`。
    ///
    /// 钉的是 [ProjectionMapper] 类注释里「空表上也返回一行」那句——它常被误读成「空表返回 0 行」。
    @Test
    void aggregateOverEmptyTableStillReturnsOneRow() {
        support().seedData(List.of());

        List<SumAndCount> rows = query().project(new ScoreSumAndCountMapper()).list();

        assertThat(rows).containsExactly(new SumAndCount(null, 0L, 2));
    }

    /// 同一个映射器实例跨查询复用：两次结果相等（映射器无状态，框架也不在它身上留行状态）。
    @Test
    void sameMapperInstanceCanBeReusedAcrossQueries() {
        StatusCountMapper mapper = new StatusCountMapper();

        List<StatusCount> first = query().project(mapper).groupBy(statusRef()).list();
        List<StatusCount> second = query().project(mapper).groupBy(statusRef()).list();

        assertThat(first).hasSize(3).isEqualTo(second);
    }

    /// 聚合映射器查询的 `count()` 被拒绝：分组/聚合规格数不出「行数」，只能抛 `UnsupportedOperationException`。
    ///
    /// 护栏在后端执行器（`jpa/exec/JpaExecutor#count` 见 `spec.aggregate()` 即抛），不在 core——
    /// core 只保证映射器产出的规格带上聚合标志，因此这条是护栏对映射器路径生效的唯一证据。
    @Test
    void countRejectsAggregateMapperQuery() {
        var aggregateQuery = query().project(new ScoreSumAndCountMapper());

        assertThatThrownBy(aggregateQuery::count).isInstanceOf(UnsupportedOperationException.class);
    }

    /// `slice(...)` 同样被拒绝：它先取 `count()` 算总数，因此撞的是同一道护栏。
    @Test
    void sliceRejectsAggregateMapperQuery() {
        var aggregateQuery = query().project(new ScoreSumAndCountMapper());

        assertThatThrownBy(() -> aggregateQuery.slice(0, 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    /// 聚合映射器 + `having`：全表聚合的那一行被 `having` 过滤掉时，结果为空。
    ///
    /// 钉的是 [ProjectionMapper] 类注释里「有 `having` 且不满足时该行被过滤掉」：类注释那条「恒返回 1 行」
    /// 只在没有 `having` 时成立，`having` 能连这一行也去掉。夹具 4 行，`count(id) > 4` 必然不满足。
    @Test
    void havingFiltersOutTheSingleAggregateRow() {
        List<SumAndCount> rows = query().project(new ScoreSumAndCountMapper())
                .having(Path.of(idRef()).count().gt(4L))
                .list();

        assertThat(rows).isEmpty();
    }

    /// 分组列 + 计数，列序固定为 `status`、`count`。
    ///
    /// 下标写成常量紧邻 `expressions()`，列序一改就在代码审查里看得见。
    private final class StatusCountMapper implements ProjectionMapper<T, StatusCount> {

        private static final int IDX_STATUS = 0;
        private static final int IDX_COUNT = 1;

        @Override
        public List<Expression<T, ?>> expressions() {
            return List.of(Path.of(statusRef()), Path.of(idRef()).count());
        }

        @Override
        public StatusCount map(Tuple row) {
            return new StatusCount((ItemStatus) row.get(IDX_STATUS), (Long) row.get(IDX_COUNT), row.size());
        }
    }

    /// 映射结果带列数：让「`map` 收到的 `Tuple` 有几列」在断言里可见；两处强转同时钉住列序（换序即 CCE）。
    private record StatusCount(ItemStatus status, long count, int columns) {}

    /// 单列（`sum(score)`）：钉住「单列也装配成长度为 1 的 `Tuple`」。
    private final class ScoreSumMapper implements ProjectionMapper<T, ScoreSum> {

        @Override
        public List<Expression<T, ?>> expressions() {
            return List.of(Path.of(scoreRef()).sum());
        }

        @Override
        public ScoreSum map(Tuple row) {
            return new ScoreSum((Integer) row.get(0), row.size());
        }
    }

    /// 列数让「单列而不是裸值」在断言里可见。
    private record ScoreSum(Integer sum, int columns) {}

    /// 两列聚合（`sum` + `count`）、不带 `groupBy`：用来证明「整表一个分组 → 恒 1 行」。
    private final class ScoreSumAndCountMapper implements ProjectionMapper<T, SumAndCount> {

        @Override
        public List<Expression<T, ?>> expressions() {
            return List.of(Path.of(scoreRef()).sum(), Path.of(idRef()).count());
        }

        @Override
        public SumAndCount map(Tuple row) {
            return new SumAndCount((Integer) row.get(0), (Long) row.get(1), row.size());
        }
    }

    /// 列数让「两列都装配进来了」在断言里可见。
    private record SumAndCount(Integer sum, long count, int columns) {}
}
