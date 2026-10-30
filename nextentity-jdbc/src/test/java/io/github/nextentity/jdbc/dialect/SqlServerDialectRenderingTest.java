package io.github.nextentity.jdbc.dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.LockMode;
import io.github.nextentity.api.Path;
import io.github.nextentity.core.builder.QueryBuilder;
import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.jdbc.compile.JdbcCompiler;
import io.github.nextentity.jdbc.compile.JdbcQuery;
import io.github.nextentity.jdbc.compile.SqlStatement;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.SnapshotItem;
import java.util.List;
import org.junit.jupiter.api.Test;

/// SQL Server 端到端渲染（设计 §6.1）：exists 的 `top (1)` 进 SELECT 头、分页必须带 ORDER BY
/// （无用户排序时补 `order by 1`）、`offset ? rows`、`fetch next ? rows only`、LIKE 显式 ESCAPE、
/// `avg` 转 float、行锁显式拒绝。
class SqlServerDialectRenderingTest {

    private final JdbcMeta meta = new JdbcMeta();
    private final JdbcCompiler compiler = new JdbcCompiler(meta, new SqlServerDialect());
    private final CapturingExecutor executor = new CapturingExecutor();

    private QueryBuilder<SnapshotItem, JdbcQuery> items() {
        return new QueryBuilder<>(meta.entityType(SnapshotItem.class), compiler, executor);
    }

    @Test
    void existsPutsTopInSelectHeadAndBindsWhere() {
        items().where(Path.of(SnapshotItem::score)).gt(10).exists();

        SqlStatement exists = compiler.exists(executor.last.spec());
        assertThat(exists.sql()).isEqualTo("select top (1) 1 from item t0 where t0.score > ?");
        assertThat(exists.parameters()).containsExactly(10);
        assertThat(exists.columnTypes()).containsExactly(Boolean.class);
    }

    @Test
    void distinctWindowWithoutUserOrderByAddsOrderByOneFallback() {
        // distinct 投影 + 窗口：core 不补主键序（QueryBuilder.java:358），因此走 MSSQL 的兜底 `order by 1`。
        items().distinct().select(Path.of(SnapshotItem::name)).list(5);

        assertThat(executor.last.sql())
                .isEqualTo("select distinct t0.name from item t0 order by 1 offset 0 rows fetch next ? rows only");
        assertThat(executor.last.parameters()).containsExactly(5);
    }

    @Test
    void pagingWithUserOrderByDoesNotAddFallback() {
        items().orderBy(Path.of(SnapshotItem::id)).asc().list(5);

        assertThat(executor.last.sql()).endsWith("order by t0.id asc offset 0 rows fetch next ? rows only");
        assertThat(executor.last.parameters()).containsExactly(5);
    }

    @Test
    void offsetWithoutLimitBindsOnlyOffset() {
        items().orderBy(Path.of(SnapshotItem::id)).asc().slice(3, 0);

        assertThat(executor.last.sql()).endsWith("order by t0.id asc offset ? rows");
        assertThat(executor.last.parameters()).containsExactly(3);
    }

    @Test
    void likeAppendsExplicitEscapeClauseAndDoublesBackslash() {
        items().where(SnapshotItem::name).like("a\\b%").list();

        assertThat(executor.last.sql()).endsWith("where t0.name like ? escape '\\'");
        assertThat(executor.last.parameters()).containsExactly("a\\\\b%");
    }

    @Test
    void averageCastsToFloat() {
        items().select(Path.of(SnapshotItem::score).avg()).list();

        assertThat(executor.last.sql()).isEqualTo("select avg(cast(t0.score as float)) from item t0");
    }

    @Test
    void lockIsRejectedExplicitly() {
        for (LockMode mode : List.of(LockMode.PESSIMISTIC_WRITE, LockMode.PESSIMISTIC_READ)) {
            assertThatThrownBy(() -> items().lock(mode).list())
                    .as("SQL Server 不支持锁模式 %s", mode)
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("SQL Server 不支持行锁")
                    .hasMessageContaining(mode.name());
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
