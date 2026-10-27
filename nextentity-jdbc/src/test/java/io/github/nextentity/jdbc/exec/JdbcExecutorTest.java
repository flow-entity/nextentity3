package io.github.nextentity.jdbc.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.Path;
import io.github.nextentity.api.model.Tuple2;
import io.github.nextentity.core.builder.QueryBuilder;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.Window;
import io.github.nextentity.jdbc.compile.JdbcCompiler;
import io.github.nextentity.jdbc.compile.JdbcQuery;
import io.github.nextentity.jdbc.compile.SqlStatement;
import io.github.nextentity.jdbc.dialect.H2Dialect;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.ItemStatus;
import io.github.nextentity.jdbc.support.OrdinalItem;
import io.github.nextentity.jdbc.support.SnapshotItem;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Table;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// 执行层（H2，§4.2「执行层」）：把 `JdbcExecutor` 交回的**原始行**钉住——实体装配、单列裸值、
/// 多列 `Object[]`、枚举按存储类型回读、`count`/`exists`、以及拒绝分支（#23）与 SQL 失败包装（#24）。
///
/// 每个用例一个独立库名（`jdbc_executor_<n>`），表由 `schema.sql` 幂等建出，种子行用普通 JDBC 直插。
class JdbcExecutorTest {

    private final JdbcMeta meta = new JdbcMeta();
    private final DataSource dataSource = new SimpleDataSource("jdbc_executor");
    private final JdbcCompiler compiler = new JdbcCompiler(meta, new H2Dialect());
    private final JdbcExecutor executor = new JdbcExecutor(dataSource, meta, compiler);

    @BeforeEach
    void seed() throws SQLException {
        applySchema(dataSource);
        execute("delete from item");
        execute("insert into item (id, name, score, score2, note, status) values"
                + " (1, 'banana', 10, 1, 'n1', 'ACTIVE'),"
                + " (2, 'cherry', 20, 2, null, 'NEW'),"
                + " (3, 'apple', 30, 3, 'n2', 'DONE')");
    }

    @Test
    void listFailureCarriesTheStatementText() {
        JdbcQuery broken = new JdbcQuery(
                new QuerySpec(
                        SnapshotItem.class, new EntitySelection(), null, List.of(), null, List.of(), Window.NONE, null),
                new SqlStatement("select * from missing_table", List.of(), List.of()));

        assertThatThrownBy(() -> executor.list(broken))
                .isInstanceOf(PersistenceException.class)
                .hasMessage("SQL 执行失败: select * from missing_table");
    }

    @Test
    void entityReadEnumFailureCarriesTheQuerySql() throws SQLException {
        execute("insert into item (id, name, score, score2, note, status)" + " values (9, 'bad', 1, 1, null, 'BOGUS')");

        assertThatThrownBy(() -> items().list())
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("SQL 执行失败: select t0.id");
    }

    private QueryBuilder<SnapshotItem, JdbcQuery> items() {
        return new QueryBuilder<>(meta.entityType(SnapshotItem.class), compiler, executor);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
            connection.commit();
        }
    }

    @Test
    void entityQueryAssemblesEntitiesAndLeavesNestedStateNull() {
        List<SnapshotItem> rows =
                items().orderBy(Path.of(SnapshotItem::id)).asc().list();

        assertThat(rows).extracting(SnapshotItem::name).containsExactly("banana", "cherry", "apple");
        assertThat(rows.get(0).score()).isEqualTo(10);
        assertThat(rows.get(0).status()).isEqualTo(ItemStatus.ACTIVE);
        assertThat(rows.get(1).note()).isNull();
    }

    @Test
    void singleColumnProjectionYieldsBareValues() {
        List<String> names = items().select(Path.of(SnapshotItem::name))
                .orderBy(Path.of(SnapshotItem::id))
                .asc()
                .list();

        assertThat(names).containsExactly("banana", "cherry", "apple");
    }

    @Test
    @SuppressWarnings("unchecked")
    void multiColumnProjectionIsAssembledIntoATuple() {
        List<?> rows = items().select(Path.of(SnapshotItem::name), Path.of(SnapshotItem::score))
                .orderBy(Path.of(SnapshotItem::id))
                .asc()
                .list();

        assertThat(rows).hasSize(3);
        Tuple2<String, Integer> first = (Tuple2<String, Integer>) rows.get(0);
        assertThat(first.get0()).isEqualTo("banana");
        assertThat(first.get1()).isEqualTo(10);
    }

    @Test
    void countAndExistsGoThroughTheDedicatedStatements() {
        assertThat(items().where(Path.of(SnapshotItem::score)).gt(10).count()).isEqualTo(2);
        assertThat(items().where(Path.of(SnapshotItem::score)).gt(100).count()).isZero();
        assertThat(items().where(Path.of(SnapshotItem::score)).gt(10).exists()).isTrue();
        assertThat(items().where(Path.of(SnapshotItem::name)).eq("missing").exists())
                .isFalse();
    }

    @Test
    void ordinalEnumIsReadBackThroughItsStorageColumn() throws SQLException {
        applySchema(dataSource);
        execute("delete from ordinal_item");
        execute("insert into ordinal_item (id, status) values (1, 1), (2, 2)");

        List<OrdinalItem> rows = new QueryBuilder<>(meta.entityType(OrdinalItem.class), compiler, executor)
                .orderBy(Path.of(OrdinalItem::id))
                .asc()
                .list();

        assertThat(rows).extracting(OrdinalItem::status).containsExactly(ItemStatus.ACTIVE, ItemStatus.DONE);
    }

    @Test
    void countOfMultiColumnDistinctIsRejected() {
        assertThatThrownBy(() -> items().distinct()
                        .select(Path.of(SnapshotItem::name), Path.of(SnapshotItem::score))
                        .count())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageStartingWith("多列 distinct 规格的 count 未实现（列数 2，方言语义不一致）：");
    }

    @Test
    void countOfAggregateSpecIsRejected() {
        assertThatThrownBy(
                        () -> items().select(Path.of(SnapshotItem::score).sum()).count())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("分组/聚合规格的 count 未实现（2.2.3 走子查询计数：" + SnapshotItem.class.getName() + "）");
    }

    @Test
    void existsOfAggregateSpecIsRejected() {
        assertThatThrownBy(
                        () -> items().select(Path.of(SnapshotItem::score).sum()).exists())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("分组/聚合规格的 exists 未实现：" + SnapshotItem.class.getName());
    }

    @Test
    void sqlFailureWrapsIntoPersistenceExceptionKeepingCause() {
        MissingTableItem probe = new MissingTableItem();

        assertThatThrownBy(() -> new QueryBuilder<>(meta.entityType(MissingTableItem.class), compiler, executor).list())
                .isInstanceOf(PersistenceException.class)
                .hasMessageStartingWith("SQL 执行失败: ")
                .hasCauseInstanceOf(SQLException.class);
    }

    @Entity
    @Table(name = "missing_table")
    static class MissingTableItem {

        @Id
        private Long id;
    }

    /// 极简 DataSource：每次操作新连接，autocommit 关闭后显式提交（执行层只读，写用 `execute` 直连）。
    private static final class SimpleDataSource implements DataSource {

        private final String url;

        SimpleDataSource(String database) {
            this.url = "jdbc:h2:mem:" + database + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, "sa", "");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger("nextentity-jdbc");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

    private static void applySchema(DataSource dataSource) throws SQLException {
        String schema;
        try (InputStream stream = JdbcExecutorTest.class.getResourceAsStream("/schema.sql")) {
            assertThat(stream).as("classpath:/schema.sql 必须存在").isNotNull();
            schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 schema.sql 失败", e);
        }
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            for (String raw : schema.split(";")) {
                String sql = raw.strip();
                if (!sql.isEmpty()) {
                    statement.execute(sql);
                }
            }
        }
    }
}
