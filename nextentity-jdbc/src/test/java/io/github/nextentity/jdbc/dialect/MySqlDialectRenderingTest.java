package io.github.nextentity.jdbc.dialect;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.core.builder.QueryBuilder;
import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.jdbc.compile.JdbcCompiler;
import io.github.nextentity.jdbc.compile.JdbcQuery;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.SnapshotItem;
import java.util.List;
import org.junit.jupiter.api.Test;

/// MySQL 端到端渲染（设计 §6.1）：`LENGTH` 必须下发 `char_length`（字符数），不能是 `length`（字节数，
/// utf8mb4 下 `'香蕉'` 算 6）；H2/PostgreSQL 的 `length` 本身就是字符语义，JPA 侧渲染 `character_length`。
class MySqlDialectRenderingTest {

    private final JdbcMeta meta = new JdbcMeta();
    private final JdbcCompiler compiler = new JdbcCompiler(meta, new MySqlDialect());
    private final CapturingExecutor executor = new CapturingExecutor();

    private QueryBuilder<SnapshotItem, JdbcQuery> items() {
        return new QueryBuilder<>(meta.entityType(SnapshotItem.class), compiler, executor);
    }

    @Test
    void lengthHookUsesCharacterCountNotByteCount() {
        assertThat(new MySqlDialect().lengthOf("t0.name")).isEqualTo("char_length(t0.name)");
    }

    @Test
    void lengthPredicateRendersCharLengthAndBindsTheComparedLiteral() {
        items().where(SnapshotItem::name).length().gt(5).list();

        assertThat(executor.last.sql())
                .isEqualTo("select t0.id, t0.name, t0.score, t0.score2, t0.note, t0.status"
                        + " from item t0 where char_length(t0.name) > ?");
        assertThat(executor.last.parameters()).containsExactly(5);
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
