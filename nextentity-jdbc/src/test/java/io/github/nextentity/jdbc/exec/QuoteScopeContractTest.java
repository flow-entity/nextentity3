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
import io.github.nextentity.jdbc.support.SnapshotItem;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/// `quote` 的作用域契约（Lead 裁决：写侧与 DDL 恒裸名，`quote` 只作用于查询渲染）。
///
/// 该契约是不对称的，因此必须由用例钉住：自定义方言覆写 `quote` 后，查询 SQL 被引用，
/// 而 `WriteSql` 产出的写入 SQL 保持裸标识符。
///
/// 依据：`tmp/plan/jdbc-design.md:643`（§6.3 quote 只作用于 4 类查询调用点，写侧与 DDL 恒裸名）、
/// `docs/DESIGN.md:462`（不变量⑤「写侧与 DDL 不走 `quote`」）、`CHANGELOG.md:107-112`
/// （「不提供的能力」记录写侧不引用标识符）。本用例是该条目的执行证据。
class QuoteScopeContractTest {

    private final JdbcMeta meta = new JdbcMeta();
    private final JdbcCompiler compiler = new JdbcCompiler(meta, new QuotingDialect());
    private final CapturingExecutor executor = new CapturingExecutor();

    @Test
    void queryRenderingUsesDialectQuote() {
        new QueryBuilder<>(meta.entityType(SnapshotItem.class), compiler, executor).list();

        assertThat(executor.last.sql())
                .isEqualTo("select t0.\"ID\", t0.\"NAME\", t0.\"SCORE\", t0.\"SCORE2\", t0.\"NOTE\", t0.\"STATUS\""
                        + " from \"ITEM\" t0");
    }

    @Test
    void writeRenderingStaysBareUnderQuotingDialect() {
        JdbcEntityType<SnapshotItem> type = meta.entityType(SnapshotItem.class);
        SnapshotItem item = new SnapshotItem(1L, "apple", 10, 10, null, ItemStatus.NEW);

        SqlStatement insert = WriteSql.insert(type, item);
        assertThat(insert.sql())
                .isEqualTo("insert into item (id, name, score, score2, note, status) values (?, ?, ?, ?, ?, ?)");
        assertThat(insert.sql()).doesNotContain("\"");

        SqlStatement update = WriteSql.update(type, item);
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
