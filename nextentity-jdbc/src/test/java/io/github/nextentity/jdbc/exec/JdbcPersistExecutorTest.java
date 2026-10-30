package io.github.nextentity.jdbc.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.jdbc.dialect.JdbcDialect;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.GeneratedItem;
import io.github.nextentity.jdbc.support.IntegerGeneratedItem;
import io.github.nextentity.jdbc.support.NullableIdItem;
import io.github.nextentity.jdbc.support.SnapshotItem;
import io.github.nextentity.jdbc.support.VersionedItem;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// 写入层（H2，写入层）：生成键回填、`@Version` 自增回写、行数 ≠ 1 时的异常族，以及空主键拒绝。
///
/// 写入器不管理事务（事务归调用方）：每个 `insert`/`update` 自己取连接、自动提交。
class JdbcPersistExecutorTest {

    private final JdbcMeta meta = new JdbcMeta();
    private final JdbcDialect dialect = JdbcDialect.of("H2");
    private final DataSource dataSource = new SimpleDataSource("jdbc_persist");

    @BeforeEach
    void clear() throws SQLException {
        applySchema(dataSource);
        execute("delete from item");
        execute("delete from generated_item");
        execute("delete from integer_generated_item");
        execute("delete from versioned_item");
    }

    @Test
    void updateWithNullEntityIsRejected() {
        JdbcPersistExecutor<SnapshotItem> persist = executor(SnapshotItem.class);

        assertThatThrownBy(() -> persist.update(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entity");
    }

    @Test
    void updateAllWithNullEntitiesIsRejected() {
        JdbcPersistExecutor<SnapshotItem> persist = executor(SnapshotItem.class);

        assertThatThrownBy(() -> persist.updateAll(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entities");
    }

    @Test
    void insertAllWithNullEntitiesIsRejected() {
        JdbcPersistExecutor<SnapshotItem> persist = executor(SnapshotItem.class);

        assertThatThrownBy(() -> persist.insertAll(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entities");
    }

    private <T> JdbcPersistExecutor<T> executor(Class<T> type) {
        return new JdbcPersistExecutor<>(dataSource, meta.entityType(type), dialect);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long count(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select count(*) from " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    @Test
    void generatedLongKeyIsWrittenBack() {
        GeneratedItem item = new GeneratedItem("anvil");
        GeneratedItem other = new GeneratedItem("banana");

        executor(GeneratedItem.class).insert(item);
        executor(GeneratedItem.class).insert(other);

        assertThat(item.id()).isNotNull();
        assertThat(other.id()).isEqualTo(item.id() + 1);
    }

    @Test
    void generatedIntegerKeyIsWrittenBackAsInteger() {
        IntegerGeneratedItem item = new IntegerGeneratedItem("anvil");

        executor(IntegerGeneratedItem.class).insert(item);

        assertThat(item.id()).isInstanceOf(Integer.class).isNotNull();
    }

    @Test
    void insertWithoutGeneratedKeyRejectsNullId() {
        assertThatThrownBy(() -> executor(NullableIdItem.class).insert(new NullableIdItem(null, "anvil")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("主键不能为 null: " + NullableIdItem.class.getName());
    }

    @Test
    void insertWithExplicitKeyWritesTheRow() throws SQLException {
        executor(SnapshotItem.class).insert(new SnapshotItem(7L, "banana", 10, 20, "n1", null));

        assertThat(count("item")).isEqualTo(1);
    }

    @Test
    void insertAllBatchesExplicitKeyRows() throws SQLException {
        executor(SnapshotItem.class)
                .insertAll(List.of(
                        new SnapshotItem(1L, "banana", 10, 20, "n1", null),
                        new SnapshotItem(2L, "cherry", 30, 40, "n2", null),
                        new SnapshotItem(3L, "apple", 50, 60, null, null)));

        assertThat(count("item")).isEqualTo(3);
    }

    @Test
    void insertAllWithGeneratedKeyAssignsEachRow() {
        GeneratedItem first = new GeneratedItem("anvil");
        GeneratedItem second = new GeneratedItem("banana");

        executor(GeneratedItem.class).insertAll(List.of(first, second));

        assertThat(first.id()).isNotNull();
        assertThat(second.id()).isEqualTo(first.id() + 1);
    }

    @Test
    void updateChangesTheRowAndBumpsVersionOnTheEntity() throws SQLException {
        VersionedItem item = new VersionedItem(7L, "banana");
        executor(VersionedItem.class).insert(item);
        assertThat(item.version()).isZero();

        item.name("cherry");
        executor(VersionedItem.class).update(item);

        assertThat(item.version()).isEqualTo(1L);
        assertThat(storedVersion(7L)).isEqualTo(1L);
        assertThat(storedName(7L)).isEqualTo("cherry");
    }

    @Test
    void updateOfMissingRowThrowsEntityNotFound() {
        NullableIdItem item = new NullableIdItem(404L, "missing");

        assertThatThrownBy(() -> executor(NullableIdItem.class).update(item))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("实体 " + NullableIdItem.class.getName() + " 不存在: id=404");
    }

    @Test
    void updateWithStaleVersionThrowsOptimisticLock() {
        VersionedItem item = new VersionedItem(7L, "banana");
        executor(VersionedItem.class).insert(item);
        item.version(5L);

        assertThatThrownBy(() -> executor(VersionedItem.class).update(item))
                .isInstanceOf(OptimisticLockException.class)
                .hasMessage("实体 " + VersionedItem.class.getName() + " 不存在: id=7, version=5");
    }

    @Test
    void conditionalUpdateSetsMatchingRowsAndReturnsAffectedCount() throws SQLException {
        executor(SnapshotItem.class)
                .insertAll(List.of(
                        new SnapshotItem(1L, "banana", 10, 20, "n1", null),
                        new SnapshotItem(2L, "cherry", 30, 40, "n2", null)));

        int affected = executor(SnapshotItem.class)
                .update()
                .set(SnapshotItem::name, "apple")
                .where(SnapshotItem::id)
                .eq(1L)
                .execute();

        assertThat(affected).isEqualTo(1);
        assertThat(itemName(1L)).isEqualTo("apple");
        assertThat(itemName(2L)).isEqualTo("cherry");
    }

    @Test
    void conditionalUpdateWithoutWhereAffectsWholeTable() throws SQLException {
        executor(SnapshotItem.class)
                .insertAll(List.of(
                        new SnapshotItem(1L, "banana", 10, 20, "n1", null),
                        new SnapshotItem(2L, "cherry", 30, 40, "n2", null)));

        int affected = executor(SnapshotItem.class)
                .update()
                .set(SnapshotItem::note, null)
                .execute();

        assertThat(affected).isEqualTo(2);
        assertThat(itemNote(1L)).isNull();
        assertThat(itemNote(2L)).isNull();
    }

    @Test
    void conditionalDeleteWithWhereRemovesOnlyMatchingRows() throws SQLException {
        executor(SnapshotItem.class)
                .insertAll(List.of(
                        new SnapshotItem(1L, "banana", 10, 20, "n1", null),
                        new SnapshotItem(2L, "cherry", 30, 40, "n2", null)));

        int affected = executor(SnapshotItem.class)
                .delete()
                .where(SnapshotItem::score)
                .gt(20)
                .execute();

        assertThat(affected).isEqualTo(1);
        assertThat(count("item")).isEqualTo(1);
    }

    @Test
    void conditionalDeleteWithoutWhereClearsTable() throws SQLException {
        executor(SnapshotItem.class)
                .insertAll(List.of(
                        new SnapshotItem(1L, "banana", 10, 20, "n1", null),
                        new SnapshotItem(2L, "cherry", 30, 40, "n2", null)));

        assertThat(executor(SnapshotItem.class).delete().execute()).isEqualTo(2);
        assertThat(count("item")).isZero();
    }

    private Object storedVersion(long id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select version from versioned_item where id = " + id)) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }

    private String itemName(long id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select name from item where id = " + id)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private String itemNote(long id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select note from item where id = " + id)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private String storedName(long id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select name from versioned_item where id = " + id)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private static void applySchema(DataSource dataSource) throws SQLException {
        String schema;
        try (InputStream stream = JdbcPersistExecutorTest.class.getResourceAsStream("/schema.sql")) {
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

    /// 极简 DataSource：每次操作新连接，自动提交。
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
}
