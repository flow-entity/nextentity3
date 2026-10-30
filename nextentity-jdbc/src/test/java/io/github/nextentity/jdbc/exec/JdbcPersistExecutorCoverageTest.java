package io.github.nextentity.jdbc.exec;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.jdbc.dialect.JdbcDialect;
import io.github.nextentity.jdbc.meta.JdbcEntityType;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.GeneratedItem;
import io.github.nextentity.jdbc.support.IntegerGeneratedItem;
import io.github.nextentity.jdbc.support.IntegerVersionedItem;
import io.github.nextentity.jdbc.support.ItemStatus;
import io.github.nextentity.jdbc.support.NullableIdItem;
import io.github.nextentity.jdbc.support.SnapshotItem;
import io.github.nextentity.jdbc.support.VersionedItem;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Table;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// `JdbcPersistExecutor` 的写侧契约（写入契约，见 USAGE §10）：批量 insert/insertAll、生成键回填（Long/Integer）、
/// 版本 NULL→0 回写、update/updateAll 的版本自增与缺行/冲突异常、以及 SQL 失败的统一包装（#24）。
class JdbcPersistExecutorCoverageTest {

    private final JdbcMeta meta = new JdbcMeta();
    private final JdbcDialect dialect = JdbcDialect.of("H2");
    private final DataSource dataSource = new SimpleDataSource("persist_coverage");

    @BeforeEach
    void seed() throws SQLException, IOException {
        applySchema(dataSource);
        execute("delete from item");
        execute("delete from versioned_item");
        execute("delete from integer_versioned_item");
        execute("delete from generated_item");
        execute("delete from integer_generated_item");
        execute("delete from nullable_id_item");
    }

    @Test
    void nullArgumentsAreRejectedUpFront() {
        JdbcPersistExecutor<SnapshotItem> persist = persist(SnapshotItem.class);

        assertThatThrownBy(() -> persist.insert(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> persist.insertAll(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> persist.update(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> persist.updateAll(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> persist.updateAll(entities(new SnapshotItem(1L, "a", 1, 1, null, null), null)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void insertRejectsNullIdentifierWithoutGeneratedKey() {
        JdbcPersistExecutor<NullableIdItem> persist = persist(NullableIdItem.class);

        assertThatThrownBy(() -> persist.insert(new NullableIdItem(null, "a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("主键不能为 null: " + NullableIdItem.class.getName());
    }

    @Test
    void emptyInsertAllProducesNoStatement() throws SQLException {
        persist(SnapshotItem.class).insertAll(List.of());

        assertThat(count("item")).isZero();
    }

    @Test
    void insertBatchWritesEveryRow() throws SQLException {
        persist(SnapshotItem.class)
                .insertAll(List.of(
                        new SnapshotItem(1L, "a", 1, 1, null, ItemStatus.NEW),
                        new SnapshotItem(2L, "b", 2, 2, "n", ItemStatus.DONE)));

        assertThat(count("item")).isEqualTo(2);
        assertThat(column("item", "name", 2L)).isEqualTo("b");
    }

    @Test
    void insertBatchRejectsNullIdentifier() {
        JdbcPersistExecutor<NullableIdItem> persist = persist(NullableIdItem.class);

        assertThatThrownBy(() -> persist.insertAll(List.of(new NullableIdItem(1L, "a"), new NullableIdItem(null, "b"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("主键不能为 null: " + NullableIdItem.class.getName());
    }

    @Test
    void insertWritesBackZeroVersionForNullVersion() throws SQLException {
        JdbcPersistExecutor<VersionedItem> longVersion = persist(VersionedItem.class);
        VersionedItem first = new VersionedItem(1L, "a");
        VersionedItem second = new VersionedItem(2L, "b");
        longVersion.insert(first);
        longVersion.insert(second);

        assertThat(first.version()).isZero();
        assertThat(second.version()).isZero();
        assertThat(versionOf("versioned_item", 1L)).isZero();

        IntegerVersionedItem integerVersion = new IntegerVersionedItem(3L, "c");
        persist(IntegerVersionedItem.class).insert(integerVersion);
        assertThat(integerVersion.version()).isZero();
        assertThat(versionOf("integer_versioned_item", 3L)).isZero();
    }

    @Test
    void generatedKeysAreBackfilledForLongAndIntegerIdentifiers() {
        GeneratedItem longKey = new GeneratedItem("long");
        persist(GeneratedItem.class).insert(longKey);
        assertThat(longKey.id()).isNotNull().isPositive();

        IntegerGeneratedItem integerKey = new IntegerGeneratedItem("int");
        persist(IntegerGeneratedItem.class).insert(integerKey);
        assertThat(integerKey.id()).isNotNull().isPositive();
    }

    @Test
    void generatedKeyInsertFailureIsWrapped() {
        JdbcPersistExecutor<MissingGenerated> persist = persist(MissingGenerated.class);

        assertThatThrownBy(() -> persist.insert(new MissingGenerated()))
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("SQL 执行失败: insert into coverage_missing_generated")
                .hasCauseInstanceOf(SQLException.class);
    }

    @Test
    void batchInsertFailureIsWrapped() {
        JdbcPersistExecutor<MissingBatch> persist = persist(MissingBatch.class);

        assertThatThrownBy(() -> persist.insertAll(List.of(new MissingBatch(1L))))
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("SQL 执行失败: ")
                .hasCauseInstanceOf(SQLException.class);
    }

    @Test
    void updateFailureIsWrapped() {
        JdbcPersistExecutor<MissingBatch> persist = persist(MissingBatch.class);

        assertThatThrownBy(() -> persist.update(new MissingBatch(1L)))
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("SQL 执行失败: ")
                .hasCauseInstanceOf(SQLException.class);
    }

    @Test
    void updateAllUpdatesEveryRowAndIncrementsVersion() throws SQLException {
        JdbcPersistExecutor<VersionedItem> persist = persist(VersionedItem.class);
        VersionedItem first = new VersionedItem(1L, "a");
        VersionedItem second = new VersionedItem(2L, "b");
        persist.insertAll(List.of(first, second));

        first.name("a2");
        second.name("b2");
        persist.updateAll(List.of(first, second));

        assertThat(column("versioned_item", "name", 1L)).isEqualTo("a2");
        assertThat(column("versioned_item", "name", 2L)).isEqualTo("b2");
        assertThat(versionOf("versioned_item", 1L)).isEqualTo(1L);
        assertThat(versionOf("versioned_item", 2L)).isEqualTo(1L);
        assertThat(first.version()).isEqualTo(1L);
        assertThat(second.version()).isEqualTo(1L);
    }

    @Test
    void updateOfMissingRowWithoutVersionThrowsEntityNotFound() {
        JdbcPersistExecutor<SnapshotItem> persist = persist(SnapshotItem.class);

        assertThatThrownBy(() -> persist.update(new SnapshotItem(999L, "ghost", null, null, null, null)))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("实体 " + SnapshotItem.class.getName() + " 不存在: id=999");
    }

    @Test
    void staleVersionThrowsOptimisticLockException() {
        JdbcPersistExecutor<VersionedItem> persist = persist(VersionedItem.class);
        persist.insert(new VersionedItem(1L, "a"));
        VersionedItem stale = new VersionedItem(1L, "b");
        stale.version(7L);

        assertThatThrownBy(() -> persist.update(stale))
                .isInstanceOf(OptimisticLockException.class)
                .hasMessage("实体 " + VersionedItem.class.getName() + " 不存在: id=1, version=7");
    }

    private <T> JdbcPersistExecutor<T> persist(Class<T> type) {
        JdbcEntityType<T> entityType = meta.entityType(type);
        return new JdbcPersistExecutor<>(dataSource, entityType, dialect);
    }

    @SafeVarargs
    private static <T> List<T> entities(T... values) {
        List<T> entities = new ArrayList<>(List.of(values));
        return entities;
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

    private String column(String table, String column, long id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select " + column + " from " + table + " where id = " + id)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private long versionOf(String table, long id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select version from " + table + " where id = " + id)) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }

    private static void applySchema(DataSource dataSource) throws SQLException, IOException {
        String schema;
        try (InputStream stream = JdbcPersistExecutorCoverageTest.class.getResourceAsStream("/schema.sql")) {
            schema = new String(stream.readAllBytes(), UTF_8);
        }
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            for (String statementSql : schema.split(";")) {
                if (!statementSql.isBlank()) {
                    statement.execute(statementSql);
                }
            }
        }
    }

    @Entity
    @Table(name = "coverage_missing_generated")
    static class MissingGenerated {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        private String name;

        protected MissingGenerated() {}
    }

    @Entity
    @Table(name = "coverage_missing_batch")
    static class MissingBatch {

        @Id
        private Long id;

        private String name;

        protected MissingBatch() {}

        MissingBatch(Long id) {
            this.id = id;
        }
    }

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
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
