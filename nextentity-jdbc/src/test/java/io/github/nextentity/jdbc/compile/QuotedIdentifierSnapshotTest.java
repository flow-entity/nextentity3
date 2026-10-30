package io.github.nextentity.jdbc.compile;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.core.builder.QueryBuilder;
import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.jdbc.dialect.H2Dialect;
import io.github.nextentity.jdbc.dialect.JdbcDialect;
import io.github.nextentity.jdbc.dialect.MySqlDialect;
import io.github.nextentity.jdbc.dialect.PostgresDialect;
import io.github.nextentity.jdbc.dialect.SqlServerDialect;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.QuotedOrder;
import io.github.nextentity.jdbc.support.QuotedOwner;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/// 标识符引用口径：注解用反引号标记过的保留字表名 / 列名才走 `JdbcDialect.quote`，
/// 内置四方言的引用字符分别为 H2/PG 双引号、MySQL 反引号、MSSQL 方括号。
///
/// 未标记的既有实体仍渲染裸名（见 {@link JdbcSqlSnapshotTest}），因此本类只钉 quoted 路径。
class QuotedIdentifierSnapshotTest {

    private final JdbcMeta meta = new JdbcMeta();

    @Test
    void h2QuotesMarkedIdentifiers() {
        assertThat(sql(new H2Dialect(), QueryBuilder::list))
                .isEqualTo("select t0.\"id\", t0.\"desc\", t0.\"key\" from \"order\" t0");
        assertThat(countSql(new H2Dialect())).isEqualTo("select count(*) from \"order\" t0");
        assertThat(existsSql(new H2Dialect())).isEqualTo("select 1 from \"order\" t0 limit 1");
        assertThat(sql(
                        new H2Dialect(),
                        query -> query.orderBy(Path.of(QuotedOrder::key)).desc().list(5)))
                .isEqualTo("select t0.\"id\", t0.\"desc\", t0.\"key\" from \"order\" t0"
                        + " order by t0.\"key\" desc limit ?");
        assertThat(sql(new H2Dialect(), query -> query.select(
                                Path.of(QuotedOrder::key),
                                Path.of(QuotedOrder::key).count())
                        .groupBy(QuotedOrder::key)
                        .having(Path.of(QuotedOrder::key).count().gt(1L))
                        .list()))
                .isEqualTo("select t0.\"key\", count(t0.\"key\") from \"order\" t0"
                        + " group by t0.\"key\" having (count(t0.\"key\") > ?)");
        assertThat(sql(new H2Dialect(), query -> query.where(ownerName().eq("alpha"))
                        .list()))
                .isEqualTo("select t0.\"id\", t0.\"desc\", t0.\"key\" from \"order\" t0"
                        + " left join \"owner\" t1 on t1.\"id\" = t0.\"order_id\" where t1.\"name\" = ?");
    }

    @Test
    void mysqlUsesBackticks() {
        assertThat(sql(new MySqlDialect(), QueryBuilder::list))
                .isEqualTo("select t0.`id`, t0.`desc`, t0.`key` from `order` t0");
        assertThat(existsSql(new MySqlDialect())).isEqualTo("select 1 from `order` t0 limit 1");
        assertThat(sql(new MySqlDialect(), query -> query.where(ownerName().eq("alpha"))
                        .list()))
                .isEqualTo("select t0.`id`, t0.`desc`, t0.`key` from `order` t0"
                        + " left join `owner` t1 on t1.`id` = t0.`order_id` where t1.`name` = ?");
    }

    @Test
    void postgresUsesDoubleQuotes() {
        assertThat(sql(new PostgresDialect(), QueryBuilder::list))
                .isEqualTo("select t0.\"id\", t0.\"desc\", t0.\"key\" from \"order\" t0");
        assertThat(countSql(new PostgresDialect())).isEqualTo("select count(*) from \"order\" t0");
        assertThat(sql(
                        new PostgresDialect(),
                        query -> query.orderBy(Path.of(QuotedOrder::key)).asc().list(5)))
                .isEqualTo("select t0.\"id\", t0.\"desc\", t0.\"key\" from \"order\" t0"
                        + " order by t0.\"key\" asc limit ?");
    }

    @Test
    void sqlServerUsesBracketsAndTopForExists() {
        assertThat(sql(new SqlServerDialect(), QueryBuilder::list))
                .isEqualTo("select t0.[id], t0.[desc], t0.[key] from [order] t0");
        assertThat(existsSql(new SqlServerDialect())).isEqualTo("select top (1) 1 from [order] t0");
        assertThat(sql(
                        new SqlServerDialect(),
                        query -> query.orderBy(Path.of(QuotedOrder::key)).desc().list(5)))
                .isEqualTo("select t0.[id], t0.[desc], t0.[key] from [order] t0"
                        + " order by t0.[key] desc offset 0 rows fetch next ? rows only");
        assertThat(sql(new SqlServerDialect(), query -> query.where(ownerName().eq("alpha"))
                        .list()))
                .isEqualTo("select t0.[id], t0.[desc], t0.[key] from [order] t0"
                        + " left join [owner] t1 on t1.[id] = t0.[order_id] where t1.[name] = ?");
    }

    private static StringPath<QuotedOrder> ownerName() {
        return EntityPath.of(QuotedOrder::owner).get(QuotedOwner::name);
    }

    private String sql(JdbcDialect dialect, Consumer<QueryBuilder<QuotedOrder, JdbcQuery>> action) {
        return run(dialect, action).last.sql();
    }

    private String countSql(JdbcDialect dialect) {
        CapturingExecutor executor = run(dialect, QueryBuilder::count);
        return new JdbcCompiler(meta, dialect).count(executor.last.spec()).sql();
    }

    private String existsSql(JdbcDialect dialect) {
        CapturingExecutor executor = run(dialect, QueryBuilder::exists);
        return new JdbcCompiler(meta, dialect).exists(executor.last.spec()).sql();
    }

    private CapturingExecutor run(JdbcDialect dialect, Consumer<QueryBuilder<QuotedOrder, JdbcQuery>> action) {
        CapturingExecutor executor = new CapturingExecutor();
        QueryBuilder<QuotedOrder, JdbcQuery> query =
                new QueryBuilder<>(meta.entityType(QuotedOrder.class), new JdbcCompiler(meta, dialect), executor);
        action.accept(query);
        return executor;
    }

    /// 只捕获编译产物、不连库的 `Executor`：count/exists 的 SQL 由测试按捕获到的 spec 重新渲染。
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
            return 0;
        }

        @Override
        public boolean exists(JdbcQuery query) {
            last = query;
            return false;
        }
    }
}
