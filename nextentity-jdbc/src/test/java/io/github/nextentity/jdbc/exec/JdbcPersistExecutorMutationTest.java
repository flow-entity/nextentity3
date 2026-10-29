package io.github.nextentity.jdbc.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.jdbc.dialect.JdbcDialect;
import io.github.nextentity.jdbc.meta.JdbcEntityType;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.GeneratedItem;
import io.github.nextentity.jdbc.support.IntegerGeneratedItem;
import io.github.nextentity.jdbc.support.IntegerVersionedItem;
import io.github.nextentity.jdbc.support.SnapshotItem;
import io.github.nextentity.jdbc.support.VersionedItem;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// 写入器在「生成键 / 版本回写 / 批量 / 空集合 / 入参校验」边界上的行为（PIT 执行面覆盖，设计 §3.11 / §5.4）。
class JdbcPersistExecutorMutationTest {

    private static final String URL = "jdbc:h2:mem:persist_mutation;MODE=MySQL;DB_CLOSE_DELAY=-1";

    private final JdbcMeta meta = new JdbcMeta();
    private final JdbcDialect dialect = JdbcDialect.of("H2");
    private final DataSource dataSource = dataSource(URL);

    @BeforeEach
    void seed() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("create table if not exists item (id bigint primary key, name varchar(255), score int, "
                    + "score2 int, note varchar(255), status varchar(32))");
            statement.execute("create table if not exists versioned_item (id bigint primary key, "
                    + "name varchar(255), version bigint not null default 0)");
            statement.execute("create table if not exists integer_versioned_item (id bigint primary key, "
                    + "name varchar(255), version int not null default 0)");
            statement.execute("create table if not exists generated_item "
                    + "(id bigint auto_increment primary key, name varchar(255))");
            statement.execute("create table if not exists integer_generated_item "
                    + "(id int auto_increment primary key, name varchar(255))");
            statement.execute("create table if not exists boxed_versioned "
                    + "(id bigint primary key, name varchar(255), version bigint)");
            statement.execute("create table if not exists generated_versioned "
                    + "(id bigint auto_increment primary key, name varchar(255), version bigint not null default 0)");
            statement.execute(
                    "create table if not exists plain_key " + "(id bigint default 7 primary key, name varchar(255))");
            statement.execute("delete from item");
            statement.execute("delete from versioned_item");
            statement.execute("delete from integer_versioned_item");
            statement.execute("delete from generated_item");
            statement.execute("delete from integer_generated_item");
            statement.execute("delete from boxed_versioned");
            statement.execute("delete from generated_versioned");
            statement.execute("delete from plain_key");
        }
    }

    @Test
    void rejectsNullArgumentsWithParameterNames() {
        JdbcPersistExecutor<VersionedItem> persist = persist(VersionedItem.class);
        VersionedItem item = new VersionedItem(1L, "a");

        assertThatThrownBy(() -> persist.insert(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("entity");
        assertThatThrownBy(() -> persist.update(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("entity");
        assertThatThrownBy(() -> persist.insertAll(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("entities");
        assertThatThrownBy(() -> persist.updateAll(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("entities");
        assertThatThrownBy(() -> persist.insertAll(Collections.singletonList(null)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("entity");
        assertThatThrownBy(() -> persist.updateAll(Collections.singletonList(null)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("entity");
        assertThat(item.version()).isZero();
    }

    @Test
    void insertAllOfPlainKeyEntityWritesEveryRowWithZeroVersion() {
        JdbcPersistExecutor<VersionedItem> persist = persist(VersionedItem.class);
        VersionedItem first = new VersionedItem(11L, "first");
        VersionedItem second = new VersionedItem(12L, "second");

        persist.insertAll(List.of(first, second));

        assertThat(first.version()).isZero();
        assertThat(second.version()).isZero();
        assertThat(count("versioned_item")).isEqualTo(2);
        assertThat(nameOf("versioned_item", 11L)).isEqualTo("first");
    }

    @Test
    void batchInsertBackfillsIntegerVersionAsZero() {
        JdbcPersistExecutor<IntegerVersionedItem> persist = persist(IntegerVersionedItem.class);
        IntegerVersionedItem first = new IntegerVersionedItem(21L, "first");
        IntegerVersionedItem second = new IntegerVersionedItem(22L, "second");

        persist.insertAll(List.of(first, second));

        assertThat(first.version()).isInstanceOf(Integer.class).isEqualTo(0);
        assertThat(second.version()).isInstanceOf(Integer.class).isEqualTo(0);
    }

    @Test
    void generatedInsertBackfillsIdentifierAndVersion() {
        GeneratedVersionedItem item = new GeneratedVersionedItem("generated");
        persist(GeneratedVersionedItem.class).insert(item);

        assertThat(item.id()).isNotNull();
        assertThat(item.version()).isZero();
        assertThat(count("generated_versioned")).isEqualTo(1);
    }

    @Test
    void insertKeepsPrefilledVersion() {
        VersionedItem item = new VersionedItem(31L, "kept");
        item.version(5L);

        persist(VersionedItem.class).insert(item);

        assertThat(item.version()).isEqualTo(5L);
        assertThat(versionOf("versioned_item", 31L)).isEqualTo(5L);
    }

    @Test
    void updateOfEntityWithoutVersionDoesNotTouchVersion() {
        SnapshotItem item = new SnapshotItem(41L, "before", 1, 2, "note", null);
        persist(SnapshotItem.class).insert(item);

        persist(SnapshotItem.class).update(new SnapshotItem(41L, "after", 3, 4, null, null));

        assertThat(nameOf("item", 41L)).isEqualTo("after");
    }

    @Test
    void generatedIdentifiersFollowTheFieldType() {
        GeneratedItem longKey = new GeneratedItem("long");
        IntegerGeneratedItem intKey = new IntegerGeneratedItem("int");

        persist(GeneratedItem.class).insert(longKey);
        persist(IntegerGeneratedItem.class).insert(intKey);

        assertThat(longKey.id()).isInstanceOf(Long.class).isEqualTo(1L);
        assertThat(intKey.id()).isInstanceOf(Integer.class).isEqualTo(1);
    }

    @Test
    void insertAllOfGeneratedEntityBackfillsEveryIdentifier() {
        GeneratedItem first = new GeneratedItem("first");
        GeneratedItem second = new GeneratedItem("second");

        persist(GeneratedItem.class).insertAll(List.of(first, second));

        assertThat(first.id()).isNotNull();
        assertThat(second.id()).isEqualTo(first.id() + 1);
        assertThat(count("generated_item")).isEqualTo(2);
    }

    @Test
    void generatedKeyWithoutAutoIncrementColumnFailsLoudly() {
        PlainKeyGenerated item = new PlainKeyGenerated("plain");
        JdbcEntityType<PlainKeyGenerated> entityType = meta.entityType(PlainKeyGenerated.class);

        assertThatThrownBy(
                        () -> new JdbcPersistExecutor<>(generatedKeylessDataSource(), entityType, dialect).insert(item))
                .isInstanceOf(PersistenceException.class)
                .hasMessage("未返回生成键: insert into plain_key (name) values (?)");
    }

    @Test
    void updateFailureCarriesTheStatementText() {
        SnapshotItem item = new SnapshotItem(51L, "ok", 1, 1, null, null);
        persist(SnapshotItem.class).insert(item);
        SnapshotItem tooLong = new SnapshotItem(51L, "x".repeat(300), 1, 1, null, null);

        assertThatThrownBy(() -> persist(SnapshotItem.class).update(tooLong))
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("SQL 执行失败: update item set");
    }

    @Test
    void primitiveLongVersionIsIncrementedAndWrittenBack() {
        VersionedItem item = new VersionedItem(81L, "primitive");
        JdbcPersistExecutor<VersionedItem> persist = persist(VersionedItem.class);
        persist.insert(item);
        assertThat(item.version()).isZero();

        item.name("primitive2");
        persist.update(item);

        assertThat(item.version()).isEqualTo(1L);
        assertThat(versionOf("versioned_item", 81L)).isEqualTo(1L);
    }

    @Test
    void boxedVersionIsIncrementedAndWrittenBackAsLong() {
        BoxedVersionedItem item = new BoxedVersionedItem(61L, "boxed");
        JdbcPersistExecutor<BoxedVersionedItem> persist = persist(BoxedVersionedItem.class);
        persist.insert(item);
        assertThat(item.version()).isEqualTo(0L);

        item.name("boxed2");
        persist.update(item);

        assertThat(item.version()).isInstanceOf(Long.class).isEqualTo(1L);
        assertThat(versionOf("boxed_versioned", 61L)).isEqualTo(1L);
    }

    @Test
    void integerVersionIsWrittenBackAsInteger() {
        IntegerVersionedItem item = new IntegerVersionedItem(71L, "integer");
        JdbcPersistExecutor<IntegerVersionedItem> persist = persist(IntegerVersionedItem.class);
        persist.insert(item);
        persist.update(item);

        assertThat(item.version()).isInstanceOf(Integer.class).isEqualTo(1);
    }

    private <T> JdbcPersistExecutor<T> persist(Class<T> type) {
        JdbcEntityType<T> entityType = meta.entityType(type);
        return new JdbcPersistExecutor<>(dataSource, entityType, dialect);
    }

    private long count(String table) {
        return queryLong("select count(*) from " + table);
    }

    private long versionOf(String table, long id) {
        return queryLong("select version from " + table + " where id = " + id);
    }

    private String nameOf(String table, long id) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                var rows = statement.executeQuery("select name from " + table + " where id = " + id)) {
            return rows.next() ? rows.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private long queryLong(String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DataSource generatedKeylessDataSource() {
        return proxy(DataSource.class, (proxy, method, arguments) -> {
            if ("getConnection".equals(method.getName())) {
                return keylessConnection();
            }
            return defaultValue(method, "GeneratedKeylessDataSource");
        });
    }

    private static Connection keylessConnection() {
        return proxy(Connection.class, (proxy, method, arguments) -> {
            if ("prepareStatement".equals(method.getName())) {
                return keylessStatement();
            }
            return defaultValue(method, "GeneratedKeylessConnection");
        });
    }

    private static PreparedStatement keylessStatement() {
        return proxy(PreparedStatement.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "executeUpdate" -> 1;
            case "getGeneratedKeys" -> emptyRows();
            default -> defaultValue(method, "GeneratedKeylessStatement");
        });
    }

    private static ResultSet emptyRows() {
        return proxy(ResultSet.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "next" -> false;
            default -> defaultValue(method, "EmptyRows");
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private static Object defaultValue(Method method, String text) {
        Class<?> type = method.getReturnType();
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == String.class) {
            return text;
        }
        return null;
    }

    private static DataSource dataSource(String url) {
        return (DataSource) Proxy.newProxyInstance(
                JdbcPersistExecutorMutationTest.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, arguments) -> {
                    if ("getConnection".equals(method.getName())) {
                        return DriverManager.getConnection(url, "sa", "");
                    }
                    if ("toString".equals(method.getName())) {
                        return "MutationDataSource[" + url + "]";
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) {
                        return false;
                    }
                    if (type == int.class) {
                        return 0;
                    }
                    if (type == long.class) {
                        return 0L;
                    }
                    return null;
                });
    }

    @Entity
    @Table(name = "boxed_versioned")
    static class BoxedVersionedItem {

        @Id
        private Long id;

        private String name;

        @Version
        private Long version;

        protected BoxedVersionedItem() {}

        BoxedVersionedItem(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        Long id() {
            return id;
        }

        String name() {
            return name;
        }

        void name(String name) {
            this.name = name;
        }

        Long version() {
            return version;
        }
    }

    @Entity
    @Table(name = "generated_versioned")
    static class GeneratedVersionedItem {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        private String name;

        @Version
        private long version;

        protected GeneratedVersionedItem() {}

        GeneratedVersionedItem(String name) {
            this.name = name;
        }

        Long id() {
            return id;
        }

        long version() {
            return version;
        }
    }

    @Entity
    @Table(name = "plain_key")
    static class PlainKeyGenerated {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        private String name;

        protected PlainKeyGenerated() {}

        PlainKeyGenerated(String name) {
            this.name = name;
        }
    }
}
