package io.github.nextentity.jdbc.dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.api.LockMode;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.core.builder.QueryBuilder;
import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.jdbc.compile.JdbcCompiler;
import io.github.nextentity.jdbc.compile.JdbcQuery;
import io.github.nextentity.jdbc.compile.SqlStatement;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.SnapshotAsset;
import io.github.nextentity.jdbc.support.SnapshotItem;
import io.github.nextentity.jdbc.support.SnapshotOwner;
import java.util.List;
import org.junit.jupiter.api.Test;

/// PostgreSQL 端到端渲染（设计 §6.1/§6.3）：`quote` 与 H2/MySQL 同为恒等（不走双引号）、
/// `limit ? offset ?`、exists 的字面量 `limit 1`、`for update of <根表别名>`、`avg` 不转型。
class PostgresDialectRenderingTest {

    private final JdbcMeta meta = new JdbcMeta();
    private final JdbcCompiler compiler = new JdbcCompiler(meta, new PostgresDialect());
    private final CapturingExecutor executor = new CapturingExecutor();

    private QueryBuilder<SnapshotItem, JdbcQuery> items() {
        return new QueryBuilder<>(meta.entityType(SnapshotItem.class), compiler, executor);
    }

    private QueryBuilder<SnapshotAsset, JdbcQuery> assets() {
        return new QueryBuilder<>(meta.entityType(SnapshotAsset.class), compiler, executor);
    }

    @Test
    void entitySelectKeepsDefaultIdentityQuote() {
        items().list();

        assertThat(executor.last.sql())
                .isEqualTo("select t0.id, t0.name, t0.score, t0.score2, t0.note, t0.status from item t0");
    }

    @Test
    void pagedQueryBindsLimitAndKeepsInjectedPrimaryKeyOrder() {
        items().list(3);

        assertThat(executor.last.sql())
                .isEqualTo("select t0.id, t0.name, t0.score, t0.score2, t0.note, t0.status from item t0"
                        + " order by t0.id asc limit ?");
        assertThat(executor.last.parameters()).containsExactly(3);
    }

    @Test
    void orderedSliceBindsLimitThenOffset() {
        items().orderBy(Path.of(SnapshotItem::id)).asc().slice(3, 5);

        assertThat(executor.last.sql()).endsWith("order by t0.id asc limit ? offset ?");
        assertThat(executor.last.parameters()).containsExactly(5, 3);
    }

    @Test
    void existsUsesLiteralLimitOneWithoutBinding() {
        items().where(Path.of(SnapshotItem::score)).gt(10).exists();

        SqlStatement exists = compiler.exists(executor.last.spec());
        assertThat(exists.sql()).isEqualTo("select 1 from item t0 where t0.score > ? limit 1");
        assertThat(exists.parameters()).containsExactly(10);
        assertThat(exists.columnTypes()).containsExactly(Boolean.class);
    }

    @Test
    void lockUsesForUpdateOfRootAlias() {
        items().lock(LockMode.PESSIMISTIC_WRITE).list();

        assertThat(executor.last.sql()).endsWith("from item t0 for update of t0");
    }

    @Test
    void readLockUsesForShareOfRootAlias() {
        items().lock(LockMode.PESSIMISTIC_READ).list();

        assertThat(executor.last.sql()).endsWith("from item t0 for share of t0");
    }

    @Test
    void unsupportedLockModesAreRejectedByTheDialect() {
        PostgresDialect dialect = new PostgresDialect();

        assertThatThrownBy(() -> dialect.lock(LockMode.OPTIMISTIC, List.of("t0")))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("方言 postgresql 不支持锁模式: OPTIMISTIC");
    }

    @Test
    void associationJoinKeepsJoinedTableAndColumns() {
        StringPath<SnapshotAsset> ownerName =
                EntityPath.of(SnapshotAsset::owner).get(SnapshotOwner::name);

        assets().where(ownerName.eq("alpha")).list();

        assertThat(executor.last.sql())
                .isEqualTo("select t0.id, t0.name from asset t0 left join owner t1"
                        + " on t1.id = t0.owner_id where t1.name = ?");
        assertThat(executor.last.parameters()).containsExactly("alpha");
    }

    @Test
    void averageIsRenderedWithoutCast() {
        items().select(Path.of(SnapshotItem::score).avg()).list();

        assertThat(executor.last.sql()).isEqualTo("select avg(t0.score) from item t0");
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
