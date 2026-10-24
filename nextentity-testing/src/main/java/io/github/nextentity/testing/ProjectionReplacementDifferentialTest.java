package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.Expression;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.ProjectionMapper;
import io.github.nextentity.api.model.Tuple;
import io.github.nextentity.api.model.Tuple2;
import java.util.List;
import org.junit.jupiter.api.Test;

/// 「一次查询只有一份投影」的**差分** oracle（USAGE §6.1 契约 3）：同一个查询入口上重复给投影，
/// 后给的**整体替换**先给的，两种形态（元组投影 / 映射器投影）互相也替换，不拼接、不报错。
///
/// 为什么需要这一格：契约写在文档里、实现也在（`QueryBuilder` 的 `applySelection` 直接覆盖字段），
/// 但一条用例都没有——「拼接而不是替换」这种错，单看某一次查询的结果永远看不出来，必须
/// **连续给两次投影**才能证伪。三条用例分别覆盖三种先后组合。
///
/// 差分分工（两条路径不相交）：
/// - 真库一侧：两次投影调用交给同一个 `EntityQuery` 入口，由它渲染成 SQL 并装配结果；
/// - 内存一侧：[Oracle] 只读 {@link #fixtureRows()} 的字段，按**最后一份投影的列序**自己装配期望值，
///   不调 nextentity 的任何生产代码，也不回读后端结果。
///
/// 断言的是「列数 + 列内容」两件事：列数由返回类型钉住（`Tuple2` / 映射结果），列内容由
/// [Oracle] 逐元素比对钉住——若实现保留了第一份投影，列内容立刻对不上；若实现把两份列拼起来，
/// 映射器会读到错位的下标（强转失败或取到别的列）。
///
/// **内存侧的行数据一律取 {@link #fixtureRows()}，不写死 {@link Fixtures#ITEM_ROWS}**：播种走
/// `@BeforeEach seed()` → `support().seedData(fixtureRows())`，而 `fixtureRows()` 可被子类覆写；
/// 内存侧若钉死默认夹具，覆写之后就变成「灌的是 A、算的是 B」，差分静默假绿。
///
/// @param <T> 后端实体类型
public abstract class ProjectionReplacementDifferentialTest<T extends Item> extends ConsistencyTestBase<T> {

    /// `select(id, name)` 之后又 `select(score, score2)`：只出后一份的两列，**不是**四列拼接。
    ///
    /// 典型 bug：`applySelection` 改成 `addAll`（或投影列表累积），第一份投影残留下来——
    /// 要么列数变成 4（`Tuple2` 装配时抛下标越界），要么第一份「赢」而返回 `(id, name)`。
    /// 期望值按**后一份**列序装配，所以两种错法都会红。
    @Test
    void selectAfterSelectKeepsOnlyTheLastProjection() {
        EntityQuery<T> entry = query();
        entry.select(idRef(), nameRef());

        List<Tuple2<Integer, Integer>> rows =
                entry.select(scoreRef(), score2Ref()).orderBy(idRef()).asc().list();
        List<Tuple2<Integer, Integer>> expected = Oracle.scoreThenScore2(fixtureRows());

        // oracle 自检：夹具里 score2 有重复值（id=1、id=2 都是 10）、score 有 null，
        // 期望值既非全表同值、也不是「按主键升序」就能冒充的
        assertThat(expected).extracting(Tuple2::get0).containsNull();
        assertThat(expected).hasSize(fixtureRows().size());
        assertThat(rows).containsExactlyElementsOf(expected);
    }

    /// `select(id, name)` 之后又 `project(三列映射器)`：按映射器的三列走（`select` 被替换）。
    ///
    /// 典型 bug：`project` 只设 mapper 不覆盖 selection —— 映射器读第 2 列时拿到的是
    /// `select` 留下的旧列（或下标越界），字段值与内存期望分叉。
    @Test
    void projectAfterSelectReplacesTheTupleProjection() {
        EntityQuery<T> entry = query();
        entry.select(idRef(), nameRef());

        List<Brief> rows =
                entry.project(new BriefMapper()).orderBy(idRef()).asc().list();
        List<Brief> expected = Oracle.briefs(fixtureRows());

        // oracle 自检：三列里 score 含 null，映射结果不是「全字段非空」的退化形态
        assertThat(expected).extracting(Brief::score).containsNull();
        assertThat(rows).containsExactlyElementsOf(expected);
    }

    /// `project(三列映射器)` 之后又 `select(id, score)`：按 `select` 的两列走（映射器被替换）。
    ///
    /// 典型 bug：反向替换漏了（只处理 `project` 覆盖 `select`，不处理反过来），
    /// 于是 SQL 仍渲染三列而调用方按 `Tuple2` 取——装配处报错或取到 `status` 冒充 `score`。
    @Test
    void selectAfterProjectReplacesTheMapper() {
        EntityQuery<T> entry = query();
        entry.project(new BriefMapper());

        List<Tuple2<Long, Integer>> rows =
                entry.select(idRef(), scoreRef()).orderBy(idRef()).asc().list();
        List<Tuple2<Long, Integer>> expected = Oracle.idThenScore(fixtureRows());

        assertThat(expected).extracting(Tuple2::get1).containsNull();
        assertThat(rows).containsExactlyElementsOf(expected);
    }

    /// 三列映射结果：字段顺序与 [BriefMapper#expressions()] 一致（`id, score, status`）。
    ///
    /// @param id     主键
    /// @param score  分数
    /// @param status 状态
    private record Brief(long id, Integer score, ItemStatus status) {

        /// 内存侧构造：只读夹具原始字段，不经任何生产代码。
        ///
        /// @param row 夹具原始行
        /// @return 期望的映射结果
        static Brief of(FixtureRow row) {
            return new Brief(row.id(), row.score(), row.status());
        }
    }

    /// 三列映射器：`id, score, status`。刻意与
    // [ProjectionReplacementDifferentialTest#selectAfterSelectKeepsOnlyTheLastProjection]
    /// 里的两列投影**不同列、不同列数**，替换没生效时一眼可辨。
    private final class BriefMapper implements ProjectionMapper<T, Brief> {

        private static final int IDX_ID = 0;

        private static final int IDX_SCORE = 1;

        private static final int IDX_STATUS = 2;

        @Override
        public List<Expression<T, ?>> expressions() {
            return List.of(Path.of(idRef()), Path.of(scoreRef()), Path.of(statusRef()));
        }

        @Override
        public Brief map(Tuple row) {
            return new Brief((Long) row.get(IDX_ID), (Integer) row.get(IDX_SCORE), (ItemStatus) row.get(IDX_STATUS));
        }
    }

    /// 内存侧期望值：全部按「后一份投影」的列序从夹具原始字段装配。
    private static final class Oracle {

        private Oracle() {}

        /// `select(score, score2)` 的期望值。
        ///
        /// @param rows 夹具原始行
        /// @return 与行一一对应的两列元组
        static List<Tuple2<Integer, Integer>> scoreThenScore2(List<FixtureRow> rows) {
            return rows.stream()
                    .map(row -> new Tuple2<>(row.score(), row.score2()))
                    .toList();
        }

        /// `select(id, score)` 的期望值。
        ///
        /// @param rows 夹具原始行
        /// @return 与行一一对应的两列元组
        static List<Tuple2<Long, Integer>> idThenScore(List<FixtureRow> rows) {
            return rows.stream().map(row -> new Tuple2<>(row.id(), row.score())).toList();
        }

        /// `project(new BriefMapper())` 的期望值。
        ///
        /// @param rows 夹具原始行
        /// @return 与行一一对应的映射结果
        static List<Brief> briefs(List<FixtureRow> rows) {
            return rows.stream().map(Brief::of).toList();
        }
    }
}
