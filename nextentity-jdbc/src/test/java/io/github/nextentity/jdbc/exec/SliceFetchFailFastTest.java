package io.github.nextentity.jdbc.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.model.Slice;
import io.github.nextentity.core.builder.QueryBuilder;
import io.github.nextentity.jdbc.compile.JdbcCompiler;
import io.github.nextentity.jdbc.compile.JdbcQuery;
import io.github.nextentity.jdbc.dialect.H2Dialect;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.SnapshotAsset;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// 真库证据：`slice()` 的空页短路不许把 JDBC 的 `fetch` 拒绝一起短路掉。
///
/// `fetch(预加载关联)` 在 JDBC 上是编译期 fail-fast，因此「同一条调用抛不抛异常」必须与表里有没有行
/// 无关。用 H2 真库跑三种数据状态（空表、有行、不带 `fetch`）钉住这一点。
class SliceFetchFailFastTest {

    private final JdbcMeta meta = new JdbcMeta();
    private final DataSource dataSource = new SimpleDataSource("jdbc_slice_fetch");
    private final JdbcCompiler compiler = new JdbcCompiler(meta, new H2Dialect());
    private final JdbcExecutor executor = new JdbcExecutor(dataSource, meta, compiler);

    @BeforeEach
    void clear() throws SQLException {
        applySchema();
        execute("delete from asset");
        execute("delete from owner");
    }

    @Test
    void emptyTableStillRejectsFetchOnSlice() {
        assertThatThrownBy(() -> assets().fetch(SnapshotAsset::owner).slice(0, 10))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("JDBC 后端不支持 fetch");
    }

    @Test
    void tableWithRowsRejectsFetchOnSlice() throws SQLException {
        execute("insert into owner (id, name) values (10, 'alpha')");
        execute("insert into asset (id, name, owner_id) values (1, 'a', 10)");

        assertThatThrownBy(() -> assets().fetch(SnapshotAsset::owner).slice(0, 10))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("JDBC 后端不支持 fetch");
    }

    @Test
    void emptyTableWithoutFetchReturnsEmptySlice() {
        Slice<SnapshotAsset> slice = assets().slice(0, 10);

        assertThat(slice.data()).isEmpty();
        assertThat(slice.total()).isZero();
    }

    private QueryBuilder<SnapshotAsset, JdbcQuery> assets() {
        return new QueryBuilder<>(meta.entityType(SnapshotAsset.class), compiler, executor);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
            connection.commit();
        }
    }

    private void applySchema() throws SQLException {
        String schema;
        try (InputStream stream = SliceFetchFailFastTest.class.getResourceAsStream("/schema.sql")) {
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

    /// 极简 DataSource：每次操作新连接，autocommit 关闭后显式提交（本用例只读，写用 `execute` 直连）。
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
