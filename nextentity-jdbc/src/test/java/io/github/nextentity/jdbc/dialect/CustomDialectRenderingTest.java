package io.github.nextentity.jdbc.dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.api.LockMode;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.core.builder.QueryBuilder;
import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.core.spec.AggregateFunction;
import io.github.nextentity.core.spec.AggregateOperand;
import io.github.nextentity.core.spec.AggregateProjection;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.ValueSelection;
import io.github.nextentity.core.spec.Window;
import io.github.nextentity.jdbc.compile.JdbcCompiler;
import io.github.nextentity.jdbc.compile.JdbcQuery;
import io.github.nextentity.jdbc.compile.SqlStatement;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.SnapshotAsset;
import io.github.nextentity.jdbc.support.SnapshotItem;
import io.github.nextentity.jdbc.support.SnapshotOwner;
import java.util.List;
import org.junit.jupiter.api.Test;

/// 自定义方言覆盖全部方言钩子（设计 §6.1）：每个钩子都在对应位置生效、参数经 sink 回流到语句，
/// 且片段占位符数与绑定数不符时立即失败。
class CustomDialectRenderingTest {

    private final JdbcMeta meta = new JdbcMeta();
    private final JdbcCompiler compiler = new JdbcCompiler(meta, new MarkerDialect());
    private final CapturingExecutor executor = new CapturingExecutor();

    private QueryBuilder<SnapshotItem, JdbcQuery> items() {
        return new QueryBuilder<>(meta.entityType(SnapshotItem.class), compiler, executor);
    }

    private QueryBuilder<SnapshotAsset, JdbcQuery> assets() {
        return new QueryBuilder<>(meta.entityType(SnapshotAsset.class), compiler, executor);
    }

    @Test
    void selectHeadAndQuoteAreUsedInEntitySelect() {
        items().list();

        assertThat(executor.last.sql())
                .isEqualTo("select /*head*/ t0.id, t0.name, t0.score, t0.score2, t0.note, t0.status" + " from item t0");
    }

    @Test
    void likeEscapeIsAppendedAfterLikePredicate() {
        items().where(SnapshotItem::name).like("a%").list();

        assertThat(executor.last.sql()).endsWith("where t0.name like ? /*like-escape*/");
        assertThat(executor.last.parameters()).containsExactly("a%");
    }

    @Test
    void averageUsesDialectAverageHook() {
        items().select(Path.of(SnapshotItem::score).avg()).list();

        assertThat(executor.last.sql()).isEqualTo("select /*head*/ customavg(t0.score) from item t0");
    }

    @Test
    void literalOnTheLeftRendersParameterBeforeExpression() {
        QuerySpec spec = new QuerySpec(
                SnapshotItem.class,
                new EntitySelection(),
                new Comparison(new Literal(10), ComparisonOperator.GT, scorePath()),
                List.of(),
                null,
                List.of(),
                Window.NONE,
                null);

        JdbcQuery query = compiler.compile(spec);

        assertThat(query.sql())
                .isEqualTo("select /*head*/ t0.id, t0.name, t0.score, t0.score2, t0.note, t0.status"
                        + " from item t0 where ? > t0.score");
        assertThat(query.parameters()).containsExactly(10);
    }

    @Test
    void averageOperandInHavingUsesDialectAverageHook() {
        AttributePath score = scorePath();
        QuerySpec spec = new QuerySpec(
                SnapshotItem.class,
                new ValueSelection(
                        List.of(new AggregateProjection(AggregateFunction.AVG, score)), false, tuple -> tuple.get(0)),
                null,
                List.of(score),
                new Comparison(
                        new AggregateOperand(AggregateFunction.AVG, score), ComparisonOperator.GT, new Literal(10)),
                List.of(),
                Window.NONE,
                null);

        JdbcQuery query = compiler.compile(spec);

        assertThat(query.sql())
                .isEqualTo("select /*head*/ customavg(t0.score) from item t0 group by t0.score"
                        + " having (customavg(t0.score) > ?)");
        assertThat(query.parameters()).containsExactly(10);
        assertThat(query.columnTypes()).containsExactly(Double.class);
    }

    private static AttributePath scorePath() {
        return new AttributePath(SnapshotItem.class, List.of("score"));
    }

    @Test
    void limitFragmentBindsThroughSink() {
        items().list(3);

        assertThat(executor.last.sql()).endsWith("from item t0 order by t0.id asc limit ? /*custom*/");
        assertThat(executor.last.parameters()).containsExactly(3);
    }

    @Test
    void existsLimitIsDelegatedToDialectWithoutBinding() {
        items().where(Path.of(SnapshotItem::score)).gt(10).exists();

        SqlStatement exists = compiler.exists(executor.last.spec());
        assertThat(exists.sql()).isEqualTo("select /*head*/ 1 from item t0 where t0.score > ? /*exists-limit*/");
        assertThat(exists.parameters()).containsExactly(10);
        assertThat(exists.columnTypes()).containsExactly(Boolean.class);
    }

    @Test
    void lockReceivesActualAliasListRootFirst() {
        StringPath<SnapshotAsset> ownerName =
                EntityPath.of(SnapshotAsset::owner).get(SnapshotOwner::name);

        assets().where(ownerName.eq("alpha")).lock(LockMode.PESSIMISTIC_WRITE).list();

        assertThat(executor.last.sql())
                .isEqualTo("select /*head*/ t0.id, t0.name from asset t0 /*lock-table*/"
                        + " left join owner t1 on t1.id = t0.owner_id where t1.name = ?"
                        + " /*lock-tail*/ t0,t1");
        assertThat(executor.last.parameters()).containsExactly("alpha");
    }

    @Test
    void fragmentWithTooManyPlaceholdersIsRejected() {
        assertThatThrownBy(() -> new JdbcCompiler(meta, new TooManyPlaceholdersDialect()).compile(windowSpec()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("方言片段占位符与绑定参数数量不符: ?=2, bound=1");
    }

    @Test
    void fragmentWithTooFewPlaceholdersIsRejected() {
        assertThatThrownBy(() -> new JdbcCompiler(meta, new TooFewPlaceholdersDialect()).compile(windowSpec()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("方言片段占位符与绑定参数数量不符: ?=1, bound=2");
    }

    private QuerySpec windowSpec() {
        items().list(3);
        return executor.last.spec();
    }

    /// 六点全覆盖的标记方言：每个钩子输出可辨识片段，便于断言「渲染点确实被调用」。
    private static final class MarkerDialect implements JdbcDialect {

        @Override
        public String name() {
            return "marker";
        }

        @Override
        public String quote(String identifier) {
            return "[" + identifier + "]";
        }

        @Override
        public String selectHead(String columns, boolean exists) {
            return "select /*head*/ " + columns;
        }

        @Override
        public String limitClause(Window window, boolean exists, boolean hasOrderBy, SqlParameterSink sink) {
            if (exists) {
                return " /*exists-limit*/";
            }
            if (window == null || window.limit() == null || window.limit() == 0) {
                return "";
            }
            sink.append(window.limit());
            return " limit ? /*custom*/";
        }

        @Override
        public LockClause lock(LockMode mode, List<String> aliases) {
            return new LockClause(" /*lock-table*/", " /*lock-tail*/ " + String.join(",", aliases));
        }

        @Override
        public String averageOf(String operand) {
            return "customavg(" + operand + ")";
        }

        @Override
        public String likeEscape() {
            return " /*like-escape*/";
        }
    }

    /// 段内两个 `?` 却只绑一个参数：说明实现有 bug，必须立刻失败而不是错位绑定。
    private static final class TooManyPlaceholdersDialect implements JdbcDialect {

        @Override
        public String name() {
            return "broken-many";
        }

        @Override
        public String limitClause(Window window, boolean exists, boolean hasOrderBy, SqlParameterSink sink) {
            sink.append(1);
            return " limit ?, ?";
        }
    }

    /// 段内一个 `?` 却绑了两个参数：同样立刻失败。
    private static final class TooFewPlaceholdersDialect implements JdbcDialect {

        @Override
        public String name() {
            return "broken-few";
        }

        @Override
        public String limitClause(Window window, boolean exists, boolean hasOrderBy, SqlParameterSink sink) {
            sink.append(1);
            sink.append(2);
            return " limit ?";
        }
    }

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
            return 100L;
        }

        @Override
        public boolean exists(JdbcQuery query) {
            last = query;
            return false;
        }
    }
}
