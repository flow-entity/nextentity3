package io.github.nextentity.jdbc.dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.LockMode;
import io.github.nextentity.core.spec.Window;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// 方言探测与 8 成员默认形态（设计 §6.1–§6.3）：内置四方言、前缀容错、unknown UOE、
/// 默认窗口五形态、PG 锁、MSSQL 的 head/分页/锁/avg/LIKE 覆写，以及自定义方言只实现 `name()` 时的默认输出。
class JdbcDialectTest {

    @Test
    void builtInProductNamesMatchCaseInsensitively() {
        assertThat(JdbcDialect.of("MySQL").name()).isEqualTo("mysql");
        assertThat(JdbcDialect.of("mysql").name()).isEqualTo("mysql");
        assertThat(JdbcDialect.of("H2").name()).isEqualTo("h2");
        assertThat(JdbcDialect.of("h2").name()).isEqualTo("h2");
        assertThat(JdbcDialect.of("PostgreSQL").name()).isEqualTo("postgresql");
        assertThat(JdbcDialect.of("Microsoft SQL Server").name()).isEqualTo("sqlserver");
    }

    @Test
    void productNameContainsVersionSuffixIsTolerated() {
        assertThat(JdbcDialect.of("PostgreSQL 16.2")).isInstanceOf(PostgresDialect.class);
        assertThat(JdbcDialect.of("Microsoft SQL Server 2022")).isInstanceOf(SqlServerDialect.class);
        assertThat(JdbcDialect.of("  H2  ").name()).isEqualTo("h2");
    }

    @Test
    void unknownProductNamesAreRejectedWithGuidance() {
        for (String product : List.of("Oracle", "MariaDB", "DB2")) {
            assertThatThrownBy(() -> JdbcDialect.of(product))
                    .as("产品名 %s 必须拒绝", product)
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("内置支持 H2 / MySQL / PostgreSQL / SQL Server")
                    .hasMessageContaining("JdbcDialect")
                    .hasMessageContaining(product);
        }
    }

    @Test
    void nullProductNameIsRejectedWithLiteralNull() {
        assertThatThrownBy(() -> JdbcDialect.of(null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageEndingWith("传入：null");
    }

    @Test
    void customDialectInheritsDefaultHookShapes() {
        JdbcDialect dialect = new CustomDialect();

        assertThat(dialect.name()).isEqualTo("custom");
        assertThat(dialect.quote("item")).isEqualTo("item");
        assertThat(dialect.selectHead("t0.id", false)).isEqualTo("select t0.id");
        assertThat(dialect.selectHead("1", true)).isEqualTo("select 1");
        assertThat(dialect.lock(LockMode.PESSIMISTIC_WRITE, List.of("t0", "t1")))
                .isEqualTo(new LockClause("", " for update"));
        assertThat(dialect.averageOf("t0.score")).isEqualTo("avg(t0.score)");
        assertThat(dialect.likeEscape()).isEmpty();
    }

    @Test
    void defaultLimitClauseRendersFiveWindowShapes() {
        JdbcDialect dialect = new CustomDialect();

        List<Object> bound = new ArrayList<>();
        SqlParameterSink sink = bound::add;

        assertThat(dialect.limitClause(Window.NONE, false, false, sink)).isEmpty();
        assertThat(bound).isEmpty();

        assertThat(dialect.limitClause(new Window(0, 5), false, false, sink)).isEqualTo(" limit ?");
        assertThat(bound).containsExactly(5);
        bound.clear();

        assertThat(dialect.limitClause(new Window(3, 5), false, false, sink)).isEqualTo(" limit ? offset ?");
        assertThat(bound).containsExactly(5, 3);
        bound.clear();

        assertThat(dialect.limitClause(new Window(3, 0), false, false, sink)).isEqualTo(" limit ? offset ?");
        assertThat(bound).containsExactly(Long.MAX_VALUE, 3);
        bound.clear();

        assertThat(dialect.limitClause(new Window(0, 0), false, false, sink)).isEmpty();
        assertThat(bound).isEmpty();
    }

    @Test
    void defaultLimitClauseTreatsNullWindowAsNoClause() {
        List<Object> bound = new ArrayList<>();

        assertThat(new CustomDialect().limitClause(null, false, false, bound::add))
                .isEmpty();
        assertThat(bound).isEmpty();
    }

    @Test
    void defaultExistsLimitIsALiteralWithoutBinding() {
        List<Object> bound = new ArrayList<>();
        String clause = new CustomDialect().limitClause(new Window(null, 1), true, false, bound::add);

        assertThat(clause).isEqualTo(" limit 1");
        assertThat(bound).isEmpty();
    }

    @Test
    void postgresOverridesOnlyLock() {
        JdbcDialect dialect = new PostgresDialect();

        assertThat(dialect.name()).isEqualTo("postgresql");
        assertThat(dialect.lock(LockMode.PESSIMISTIC_WRITE, List.of("t0", "t1")))
                .isEqualTo(new LockClause("", " for update of t0"));
        assertThat(dialect.selectHead("1", true)).isEqualTo("select 1");
        assertThat(dialect.likeEscape()).isEmpty();
        assertThat(dialect.averageOf("t0.score")).isEqualTo("avg(t0.score)");

        List<Object> bound = new ArrayList<>();
        assertThat(dialect.limitClause(new Window(0, 5), false, true, bound::add))
                .isEqualTo(" limit ?");
        assertThat(bound).containsExactly(5);
    }

    @Test
    void sqlServerPutsExistsLimitInSelectHead() {
        JdbcDialect dialect = new SqlServerDialect();

        assertThat(dialect.name()).isEqualTo("sqlserver");
        assertThat(dialect.selectHead("1", true)).isEqualTo("select top (1) 1");
        assertThat(dialect.selectHead("t0.id", false)).isEqualTo("select t0.id");
        assertThat(dialect.limitClause(new Window(null, 1), true, false, value -> {
                    throw new AssertionError("exists 不应绑参");
                }))
                .isEmpty();
    }

    @Test
    void sqlServerLimitClauseTreatsNullWindowAsNoClause() {
        List<Object> bound = new ArrayList<>();

        assertThat(new SqlServerDialect().limitClause(null, false, false, bound::add))
                .isEmpty();
        assertThat(bound).isEmpty();
    }

    @Test
    void sqlServerLimitClauseCoversAllWindowShapes() {
        JdbcDialect dialect = new SqlServerDialect();
        List<Object> bound = new ArrayList<>();
        SqlParameterSink sink = bound::add;

        assertThat(dialect.limitClause(Window.NONE, false, false, sink)).isEmpty();
        assertThat(bound).isEmpty();

        assertThat(dialect.limitClause(new Window(0, 5), false, true, sink))
                .isEqualTo(" offset 0 rows fetch next ? rows only");
        assertThat(bound).containsExactly(5);
        bound.clear();

        assertThat(dialect.limitClause(new Window(3, 5), false, true, sink))
                .isEqualTo(" offset ? rows fetch next ? rows only");
        assertThat(bound).containsExactly(3, 5);
        bound.clear();

        assertThat(dialect.limitClause(new Window(3, 0), false, false, sink)).isEqualTo(" order by 1 offset ? rows");
        assertThat(bound).containsExactly(3);
        bound.clear();

        assertThat(dialect.limitClause(new Window(0, 0), false, false, sink)).isEmpty();
        assertThat(bound).isEmpty();
    }

    @Test
    void sqlServerRejectsLockExplicitly() {
        assertThatThrownBy(() -> new SqlServerDialect().lock(LockMode.PESSIMISTIC_WRITE, List.of("t0")))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("SQL Server 不支持行锁")
                .hasMessageContaining("PESSIMISTIC_WRITE");
    }

    @Test
    void sqlServerAverageCastsToFloatAndLikeUsesEscape() {
        JdbcDialect dialect = new SqlServerDialect();

        assertThat(dialect.averageOf("t0.score")).isEqualTo("avg(cast(t0.score as float))");
        assertThat(dialect.likeEscape()).isEqualTo(" escape '\\'");
        assertThat(dialect.quote("item")).isEqualTo("item");
    }

    /// 只实现 `name()` 的自定义方言：其余成员必须回落到默认形态。
    private static final class CustomDialect implements JdbcDialect {

        @Override
        public String name() {
            return "custom";
        }
    }
}
