package io.github.nextentity.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.Path;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jdbc.dialect.JdbcDialect;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Table;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/// 门面装配（设计 §2.1）：`DataSource` 构造期探测产品名、显式方言注入、四个零件的接线、
/// 以及探测失败/未知产品名的拒绝（#20）。
class JdbcEntityOperationsFactoryTest {

    private static final String URL = "jdbc:h2:mem:factory_probe;MODE=MySQL;DB_CLOSE_DELAY=-1";

    @Test
    void detectsDialectFromDataSource() {
        JdbcEntityOperationsFactory factory = new JdbcEntityOperationsFactory(new SimpleDataSource(URL));

        assertThat(factory.dialect().name()).isEqualTo("h2");
    }

    @Test
    void explicitDialectIsKept() {
        JdbcDialect mysql = JdbcDialect.of("MySQL");
        JdbcEntityOperationsFactory factory = new JdbcEntityOperationsFactory(new SimpleDataSource(URL), mysql);

        assertThat(factory.dialect()).isSameAs(mysql);
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThatThrownBy(() -> new JdbcEntityOperationsFactory(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("dataSource");
        assertThatThrownBy(() -> new JdbcEntityOperationsFactory(null, JdbcDialect.of("H2")))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("dataSource");
        assertThatThrownBy(() -> new JdbcEntityOperationsFactory(new SimpleDataSource(URL), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("dialect");
    }

    @Test
    void assembledOperationsInsertAndQueryEntities() throws SQLException {
        DataSource dataSource = new SimpleDataSource(URL);
        execute(dataSource, "create table if not exists factory_item (id bigint primary key, name varchar(255))");
        execute(dataSource, "delete from factory_item");
        EntityOperations<FactoryItem> operations =
                new JdbcEntityOperationsFactory(dataSource).operations(FactoryItem.class);

        operations.insert(new FactoryItem(1L, "a"));
        operations.insert(new FactoryItem(2L, "b"));

        assertThat(operations
                        .query()
                        .select(Path.of(FactoryItem::name))
                        .orderBy(Path.of(FactoryItem::id))
                        .asc()
                        .list())
                .containsExactly("a", "b");
        assertThat(operations.query().count()).isEqualTo(2);
    }

    @Test
    void operationsOfUnmappedClassIsRejected() {
        assertThatThrownBy(() -> new JdbcEntityOperationsFactory(new SimpleDataSource(URL)).operations(Object.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("缺少 @Entity 注解: " + Object.class.getName());
    }

    @Test
    void unknownProductNameIsRejected() {
        DataSource dataSource = dataSourceWithConnection(connectionWithProduct("Oracle"));

        assertThatThrownBy(() -> new JdbcEntityOperationsFactory(dataSource))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("不支持的数据库产品: Oracle（本期只支持 MySQL / H2）");
    }

    @Test
    void connectionFailureIsWrapped() {
        DataSource dataSource = (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        throw new SQLException("boom");
                    }
                    return defaultValue(method.getReturnType());
                });

        assertThatThrownBy(() -> new JdbcEntityOperationsFactory(dataSource))
                .isInstanceOf(PersistenceException.class)
                .hasMessage("获取数据库产品名失败，无法识别 JDBC 方言")
                .hasCauseInstanceOf(SQLException.class);
    }

    @Test
    void explicitDialectSkipsDetection() {
        DataSource failing = (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        throw new SQLException("boom");
                    }
                    return defaultValue(method.getReturnType());
                });

        JdbcEntityOperationsFactory factory = new JdbcEntityOperationsFactory(failing, JdbcDialect.of("H2"));

        assertThat(factory.dialect().name()).isEqualTo("h2");
    }

    private static DataSource dataSourceWithConnection(Connection connection) {
        return (DataSource) Proxy.newProxyInstance(
                JdbcEntityOperationsFactoryTest.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) ->
                        "getConnection".equals(method.getName()) ? connection : defaultValue(method.getReturnType()));
    }

    private static Connection connectionWithProduct(String product) {
        DatabaseMetaData metadata = (DatabaseMetaData) Proxy.newProxyInstance(
                JdbcEntityOperationsFactoryTest.class.getClassLoader(),
                new Class<?>[] {DatabaseMetaData.class},
                (proxy, method, args) -> "getDatabaseProductName".equals(method.getName())
                        ? product
                        : defaultValue(method.getReturnType()));
        return (Connection) Proxy.newProxyInstance(
                JdbcEntityOperationsFactoryTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) ->
                        "getMetaData".equals(method.getName()) ? metadata : defaultValue(method.getReturnType()));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == float.class) {
            return 0F;
        }
        return type == char.class ? '\0' : 0;
    }

    private static void execute(DataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Entity
    @Table(name = "factory_item")
    static class FactoryItem {

        @Id
        private Long id;

        private String name;

        protected FactoryItem() {}

        FactoryItem(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        Long id() {
            return id;
        }

        String name() {
            return name;
        }
    }

    private static final class SimpleDataSource implements DataSource {

        private final String url;

        SimpleDataSource(String url) {
            this.url = url;
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
