package io.github.nextentity.jdbc.exec;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.core.builder.QueryBuilder;
import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.jdbc.compile.JdbcCompiler;
import io.github.nextentity.jdbc.compile.JdbcQuery;
import io.github.nextentity.jdbc.compile.SqlStatement;
import io.github.nextentity.jdbc.dialect.JdbcDialect;
import io.github.nextentity.jdbc.meta.JdbcEntityType;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.ItemStatus;
import io.github.nextentity.jdbc.support.QuotedOwner;
import io.github.nextentity.jdbc.support.SnapshotItem;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/// `quote` 的作用域契约：meta 标记为 quoted 的标识符，查询渲染与写侧（INSERT/UPDATE）都过
/// `JdbcDialect#quote`；未 quoted 的名字即使方言覆写了 `quote` 也保持裸名（既有快照零 churn）。
///
/// 依据：DESIGN §6.1 的不变量（quote 只作用于 quoted 标识符，调用点见下），`docs/DESIGN.md` 不变量⑤要求
/// 「未 quoted 的标识符恒裸名」。
class QuoteScopeContractTest {

    private final JdbcMeta meta = new JdbcMeta();
    private final JdbcDialect quoting = new QuotingDialect();
    private final JdbcCompiler compiler = new JdbcCompiler(meta, quoting);
    private final CapturingExecutor executor = new CapturingExecutor();

    @Test
    void quotedNamesUseDialectQuoteInQueryRendering() {
        new QueryBuilder<>(meta.entityType(QuotedOwner.class), compiler, executor).list();

        assertThat(executor.last.sql()).isEqualTo("select t0.\"ID\", t0.\"NAME\" from \"OWNER\" t0");
    }

    @Test
    void unquotedNamesStayBareEvenUnderQuotingDialect() {
        new QueryBuilder<>(meta.entityType(SnapshotItem.class), compiler, executor).list();

        assertThat(executor.last.sql())
                .isEqualTo("select t0.id, t0.name, t0.score, t0.score2, t0.note, t0.status from item t0");
    }

    @Test
    void writeRenderingOfUnquotedNamesStaysBareUnderQuotingDialect() {
        JdbcEntityType<SnapshotItem> type = meta.entityType(SnapshotItem.class);
        SnapshotItem item = new SnapshotItem(1L, "apple", 10, 10, null, ItemStatus.NEW);

        SqlStatement insert = WriteSql.insert(type, item, quoting);
        assertThat(insert.sql())
                .isEqualTo("insert into item (id, name, score, score2, note, status) values (?, ?, ?, ?, ?, ?)");
        assertThat(insert.sql()).doesNotContain("\"");

        SqlStatement update = WriteSql.update(type, item, quoting);
        assertThat(update.sql()).isEqualTo("update item set name=?, score=?, score2=?, note=?, status=? where id=?");
        assertThat(update.sql()).doesNotContain("\"");
    }

    /// 引用一切标识符并折成大写的自定义方言；其余钩子走默认实现。
    private static final class QuotingDialect implements JdbcDialect {

        @Override
        public String name() {
            return "quoting";
        }

        @Override
        public String quote(String identifier) {
            return "\"" + identifier.toUpperCase(Locale.ROOT) + "\"";
        }
    }

    /// 只捕获编译产物、不连库的 `Executor`。
    private static final class CapturingExecutor implements Executor<JdbcQuery> {

        private JdbcQuery last;

        @Override
        public <R> List<R> list(JdbcQuery query) {
            last = query;
            return List.of();
        }

        @Override
        public long count(JdbcQuery query) {
            throw new AssertionError("本用例只走 list()");
        }

        @Override
        public boolean exists(JdbcQuery query) {
            throw new AssertionError("本用例只走 list()");
        }
    }
}
