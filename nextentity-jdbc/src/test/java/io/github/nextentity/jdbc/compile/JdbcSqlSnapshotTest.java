package io.github.nextentity.jdbc.compile;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.api.LockMode;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.core.builder.QueryBuilder;
import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.jdbc.dialect.H2Dialect;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.SnapshotAsset;
import io.github.nextentity.jdbc.support.SnapshotItem;
import io.github.nextentity.jdbc.support.SnapshotOwner;
import java.util.List;
import org.junit.jupiter.api.Test;

/// 查询 SQL 整句快照（§7.5，不连库）：`JdbcCompiler` 渲染出的 `sql()` 与 `parameters()` 逐字钉住。
///
/// 驱动方式：`JdbcMeta` + `H2Dialect` + 捕获式 `Executor`，查什么由 core 的 `QueryBuilder` 生成规格；
/// 本类只断言编译产物，后续接线类（tests-jdbc）用同一 compiler 打真库。渲染口径见设计 §3.0：
/// 全小写关键字、裸标识符（无引号）、根表 `t0`、join 目标 `t1`、参数按渲染顺序绑定。
class JdbcSqlSnapshotTest {

    private final JdbcMeta meta = new JdbcMeta();
    private final JdbcCompiler compiler = new JdbcCompiler(meta, new H2Dialect());
    private final CapturingExecutor executor = new CapturingExecutor();

    private QueryBuilder<SnapshotItem, JdbcQuery> items() {
        return new QueryBuilder<>(meta.entityType(SnapshotItem.class), compiler, executor);
    }

    private QueryBuilder<SnapshotAsset, JdbcQuery> assets() {
        return new QueryBuilder<>(meta.entityType(SnapshotAsset.class), compiler, executor);
    }

    // ---------------------------------------------------------------- 实体查询

    @Test
    void entitySelectListsScalarColumnsInDeclarationOrder() {
        items().list();

        assertThat(executor.last.sql())
                .isEqualTo("select t0.id, t0.name, t0.score, t0.score2, t0.note, t0.status from item t0");
        assertThat(executor.last.parameters()).isEmpty();
    }

    @Test
    void entitySelectPropagatesNoOrderByWithoutWindow() {
        items().where(Path.of(SnapshotItem::score)).gt(10).list();

        assertThat(executor.last.sql())
                .isEqualTo("select t0.id, t0.name, t0.score, t0.score2, t0.note, t0.status from item t0"
                        + " where t0.score > ?");
        assertThat(executor.last.parameters()).containsExactly(10);
    }

    // ---------------------------------------------------------------- 条件算子

    @Test
    void comparisonOperatorsRenderSymbolsAndOrderParameters() {
        items().where(Path.of(SnapshotItem::score)).ne(1).list();
        assertThat(executor.last.sql()).endsWith("where t0.score <> ?");

        items().where(Path.of(SnapshotItem::score)).ge(2).list();
        assertThat(executor.last.sql()).endsWith("where t0.score >= ?");

        items().where(Path.of(SnapshotItem::score)).lt(3).list();
        assertThat(executor.last.sql()).endsWith("where t0.score < ?");

        items().where(Path.of(SnapshotItem::score)).le(4).list();
        assertThat(executor.last.sql()).endsWith("where t0.score <= ?");
    }

    @Test
    void likeAndContainsBindTheirPattern() {
        items().where(SnapshotItem::name).like("ba%").list();
        assertThat(executor.last.sql()).endsWith("where t0.name like ?");
        assertThat(executor.last.parameters()).containsExactly("ba%");

        items().where(SnapshotItem::name).contains("nan").list();
        assertThat(executor.last.sql()).endsWith("where t0.name like ?");
        assertThat(executor.last.parameters()).containsExactly("%nan%");
    }

    @Test
    void betweenBindsLowerAndUpperInOrder() {
        items().where(Path.of(SnapshotItem::score)).between(10, 20).list();

        assertThat(executor.last.sql()).endsWith("where t0.score between ? and ?");
        assertThat(executor.last.parameters()).containsExactly(10, 20);
    }

    @Test
    void betweenIsNegatedByWrappingInNot() {
        items().where(Path.of(SnapshotItem::score)).notBetween(10, 20).list();

        assertThat(executor.last.sql()).endsWith("where not (t0.score between ? and ?)");
        assertThat(executor.last.parameters()).containsExactly(10, 20);
    }

    @Test
    void inListBindsEveryElement() {
        items().where(Path.of(SnapshotItem::id)).in(List.of(1L, 2L)).list();

        assertThat(executor.last.sql()).endsWith("where t0.id in (?, ?)");
        assertThat(executor.last.parameters()).containsExactly(1L, 2L);
    }

    @Test
    void inListWithSingleElementStillRendersOnePlaceholder() {
        items().where(Path.of(SnapshotItem::id)).in(List.of(7L)).list();

        assertThat(executor.last.sql()).endsWith("where t0.id in (?)");
        assertThat(executor.last.parameters()).containsExactly(7L);
    }

    @Test
    void notInListKeepsNegationKeyword() {
        items().where(Path.of(SnapshotItem::id)).notIn(List.of(1L, 2L)).list();

        assertThat(executor.last.sql()).endsWith("where t0.id not in (?, ?)");
        assertThat(executor.last.parameters()).containsExactly(1L, 2L);
    }

    @Test
    void nullChecksHaveNoParameters() {
        items().where(Path.of(SnapshotItem::note)).isNull().list();
        assertThat(executor.last.sql()).endsWith("where t0.note is null");
        assertThat(executor.last.parameters()).isEmpty();

        items().where(Path.of(SnapshotItem::note)).isNotNull().list();
        assertThat(executor.last.sql()).endsWith("where t0.note is not null");
        assertThat(executor.last.parameters()).isEmpty();
    }

    @Test
    void logicalCombinationsParenthesizeTheirOperands() {
        items().where(Path.of(SnapshotItem::score)
                        .gt(10)
                        .and(Path.of(SnapshotItem::score2).lt(5)))
                .list();

        assertThat(executor.last.sql()).endsWith("where (t0.score > ? and t0.score2 < ?)");
        assertThat(executor.last.parameters()).containsExactly(10, 5);
    }

    @Test
    void nestedLogicalKeepsInnerParentheses() {
        items().where(Path.of(SnapshotItem::score)
                        .gt(10)
                        .or(Path.of(SnapshotItem::name)
                                .eq("a")
                                .and(Path.of(SnapshotItem::note).isNull())))
                .list();

        assertThat(executor.last.sql()).endsWith("where (t0.score > ? or (t0.name = ? and t0.note is null))");
        assertThat(executor.last.parameters()).containsExactly(10, "a");
    }

    @Test
    void negationWrapsOperandInNot() {
        items().where(Path.of(SnapshotItem::score).gt(10).not()).list();

        assertThat(executor.last.sql()).endsWith("where not (t0.score > ?)");
        assertThat(executor.last.parameters()).containsExactly(10);
    }

    @Test
    void columnComparisonBindsNothing() {
        items().where(Path.of(SnapshotItem::score).gt(Path.of(SnapshotItem::score2)))
                .list();

        assertThat(executor.last.sql()).endsWith("where t0.score > t0.score2");
        assertThat(executor.last.parameters()).isEmpty();
    }

    // ---------------------------------------------------------------- 排序 / 分页

    @Test
    void orderByRendersExplicitDirection() {
        items().orderBy(Path.of(SnapshotItem::name))
                .asc()
                .orderBy(Path.of(SnapshotItem::score))
                .desc()
                .list();

        assertThat(executor.last.sql()).endsWith("order by t0.name asc, t0.score desc");
        assertThat(executor.last.parameters()).isEmpty();
    }

    @Test
    void listWithLimitBindsLimitAfterAutoOrderBy() {
        items().list(5);

        assertThat(executor.last.sql())
                .isEqualTo("select t0.id, t0.name, t0.score, t0.score2, t0.note, t0.status from item t0"
                        + " order by t0.id asc limit ?");
        assertThat(executor.last.parameters()).containsExactly(5);
    }

    @Test
    void sliceBindsLimitAndOffsetInThatOrder() {
        items().orderBy(Path.of(SnapshotItem::id)).asc().slice(5, 10);

        assertThat(executor.last.sql())
                .isEqualTo("select t0.id, t0.name, t0.score, t0.score2, t0.note, t0.status from item t0"
                        + " order by t0.id asc limit ? offset ?");
        assertThat(executor.last.parameters()).containsExactly(10, 5);
    }

    @Test
    void sliceWithoutLimitUsesMaxValueSentinel() {
        items().orderBy(Path.of(SnapshotItem::id)).asc().slice(3, 0);

        assertThat(executor.last.sql())
                .isEqualTo("select t0.id, t0.name, t0.score, t0.score2, t0.note, t0.status from item t0"
                        + " order by t0.id asc limit ? offset ?");
        assertThat(executor.last.parameters()).containsExactly(Long.MAX_VALUE, 3);
    }

    @Test
    void listWithoutLimitEmitsNoWindowClause() {
        items().orderBy(Path.of(SnapshotItem::id)).asc().list(0);

        assertThat(executor.last.sql()).doesNotContain("limit");
    }

    // ---------------------------------------------------------------- 投影

    @Test
    void singleColumnProjectionRendersBareColumn() {
        items().select(Path.of(SnapshotItem::name)).list();

        assertThat(executor.last.sql()).isEqualTo("select t0.name from item t0");
    }

    @Test
    void multiColumnProjectionKeepsProjectionOrder() {
        items().select(Path.of(SnapshotItem::name), Path.of(SnapshotItem::score))
                .list();

        assertThat(executor.last.sql()).isEqualTo("select t0.name, t0.score from item t0");
    }

    @Test
    void distinctProjectionRendersDistinctRightAfterSelect() {
        items().distinct().select(Path.of(SnapshotItem::status)).list();

        assertThat(executor.last.sql()).isEqualTo("select distinct t0.status from item t0");
    }

    @Test
    void aggregateProjectionsUseTheirSqlFunctions() {
        items().select(Path.of(SnapshotItem::id).count()).list();
        assertThat(executor.last.sql()).isEqualTo("select count(t0.id) from item t0");

        items().select(Path.of(SnapshotItem::score).max()).list();
        assertThat(executor.last.sql()).isEqualTo("select max(t0.score) from item t0");

        items().select(Path.of(SnapshotItem::score).min()).list();
        assertThat(executor.last.sql()).isEqualTo("select min(t0.score) from item t0");

        items().select(Path.of(SnapshotItem::score).avg()).list();
        assertThat(executor.last.sql()).isEqualTo("select avg(t0.score) from item t0");

        items().select(Path.of(SnapshotItem::score).sum()).list();
        assertThat(executor.last.sql()).isEqualTo("select sum(t0.score) from item t0");
    }

    @Test
    void arithmeticProjectionIsParenthesized() {
        items().select(Path.of(SnapshotItem::score).add(1)).list();

        assertThat(executor.last.sql()).isEqualTo("select (t0.score + ?) from item t0");
        assertThat(executor.last.parameters()).containsExactly(1);
    }

    @Test
    void groupByAndHavingKeepClauseOrder() {
        items().select(Path.of(SnapshotItem::status), Path.of(SnapshotItem::id).count())
                .groupBy(SnapshotItem::status)
                .having(Path.of(SnapshotItem::id).count().gt(1L))
                .list();

        assertThat(executor.last.sql())
                .isEqualTo("select t0.status, count(t0.id) from item t0"
                        + " group by t0.status having (count(t0.id) > ?)");
        assertThat(executor.last.parameters()).containsExactly(1L);
    }

    @Test
    void havingWithoutGroupByIsStillRendered() {
        items().select(Path.of(SnapshotItem::id).count())
                .having(Path.of(SnapshotItem::id).count().gt(1L))
                .list();

        assertThat(executor.last.sql()).isEqualTo("select count(t0.id) from item t0 having (count(t0.id) > ?)");
        assertThat(executor.last.parameters()).containsExactly(1L);
    }

    // ---------------------------------------------------------------- 关联

    @Test
    void associationPathRegistersLeftJoinAndQualifiesColumns() {
        StringPath<SnapshotAsset> ownerName =
                EntityPath.of(SnapshotAsset::owner).get(SnapshotOwner::name);

        assets().where(ownerName.eq("alpha")).list();

        assertThat(executor.last.sql())
                .isEqualTo("select t0.id, t0.name from asset t0"
                        + " left join owner t1 on t1.id = t0.owner_id where t1.name = ?");
        assertThat(executor.last.parameters()).containsExactly("alpha");
    }

    @Test
    void associationIsNullUsesSameLeftJoin() {
        StringPath<SnapshotAsset> ownerName =
                EntityPath.of(SnapshotAsset::owner).get(SnapshotOwner::name);

        assets().where(ownerName.isNull()).list();

        assertThat(executor.last.sql())
                .isEqualTo("select t0.id, t0.name from asset t0"
                        + " left join owner t1 on t1.id = t0.owner_id where t1.name is null");
        assertThat(executor.last.parameters()).isEmpty();
    }

    @Test
    void associationColumnCanBeOrdered() {
        StringPath<SnapshotAsset> ownerName =
                EntityPath.of(SnapshotAsset::owner).get(SnapshotOwner::name);

        assets().orderBy(ownerName).asc().list();

        assertThat(executor.last.sql())
                .isEqualTo("select t0.id, t0.name from asset t0"
                        + " left join owner t1 on t1.id = t0.owner_id order by t1.name asc");
    }

    // ---------------------------------------------------------------- 终止符

    @Test
    void countUsesCountStarAndKeepsWhereButDropsOrder() {
        items().where(Path.of(SnapshotItem::score))
                .gt(10)
                .orderBy(Path.of(SnapshotItem::name))
                .asc()
                .count();

        SqlStatement count = compiler.count(executor.last.spec());
        assertThat(count.sql()).isEqualTo("select count(*) from item t0 where t0.score > ?");
        assertThat(count.parameters()).containsExactly(10);
        assertThat(count.columnTypes()).containsExactly(Long.class);
    }

    @Test
    void countOfSingleColumnDistinctUsesCountDistinct() {
        items().distinct().select(Path.of(SnapshotItem::name)).count();

        SqlStatement count = compiler.count(executor.last.spec());
        assertThat(count.sql()).isEqualTo("select count(distinct t0.name) from item t0");
        assertThat(count.parameters()).isEmpty();
        assertThat(count.columnTypes()).containsExactly(Long.class);
    }

    @Test
    void existsRendersSelectOneWithLimitOne() {
        items().where(Path.of(SnapshotItem::score)).gt(10).exists();

        SqlStatement exists = compiler.exists(executor.last.spec());
        assertThat(exists.sql()).isEqualTo("select 1 from item t0 where t0.score > ? limit 1");
        assertThat(exists.parameters()).containsExactly(10);
        assertThat(exists.columnTypes()).containsExactly(Boolean.class);
    }

    // ---------------------------------------------------------------- 锁

    @Test
    void pessimisticWriteAppendsForUpdateLast() {
        items().where(Path.of(SnapshotItem::score))
                .gt(10)
                .lock(LockMode.PESSIMISTIC_WRITE)
                .list();

        assertThat(executor.last.sql())
                .isEqualTo("select t0.id, t0.name, t0.score, t0.score2, t0.note, t0.status from item t0"
                        + " where t0.score > ? for update");
        assertThat(executor.last.parameters()).containsExactly(10);
    }

    @Test
    void forUpdateFollowsWindowClause() {
        items().orderBy(Path.of(SnapshotItem::id))
                .asc()
                .lock(LockMode.PESSIMISTIC_WRITE)
                .list(3);

        assertThat(executor.last.sql())
                .isEqualTo("select t0.id, t0.name, t0.score, t0.score2, t0.note, t0.status from item t0"
                        + " order by t0.id asc limit ? for update");
        assertThat(executor.last.parameters()).containsExactly(3);
    }

    /// 捕获式执行器：`QueryBuilder` 只把编译产物交给它，不连库。
    private static final class CapturingExecutor implements Executor<JdbcQuery> {

        private JdbcQuery last;

        @Override
        public <R> List<R> list(JdbcQuery query) {
            last = query;
            return List.of();
        }

        @Override
        public long count(JdbcQuery query) {
            last = query;
            // 交给分页用例的「总数」：必须大于 offset，`slice` 才会真的执行列表查询
            return 100L;
        }

        @Override
        public boolean exists(JdbcQuery query) {
            last = query;
            return false;
        }
    }
}
