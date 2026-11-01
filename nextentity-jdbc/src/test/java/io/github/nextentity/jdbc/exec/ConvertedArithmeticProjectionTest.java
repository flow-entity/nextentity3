package io.github.nextentity.jdbc.exec;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.core.builder.QueryBuilder;
import io.github.nextentity.jdbc.compile.JdbcCompiler;
import io.github.nextentity.jdbc.compile.JdbcQuery;
import io.github.nextentity.jdbc.dialect.H2Dialect;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.ConvertedLongItem;
import io.github.nextentity.jdbc.support.SnapshotItem;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// 算术投影对 `@Convert` 列的口径：与 `sum()` 同族，结果域是**转换器的数据库类型**，静态类型仍是属性类型。
///
/// 契约出处是 `docs/USAGE.md` §7 的已知限制：`AttributeConverter` 不声明「加性是否保值」，非保值转换器上的
/// 算值不落在属性值域内，所以本层不做反转换（`RowReader#enumAttribute` 对 `ComputedProjection` 返回 null）。
/// 本类钉住现状，改运行期行为（加反转换）会让主断言先红。
class ConvertedArithmeticProjectionTest {

    private final JdbcMeta meta = new JdbcMeta();
    private final DataSource dataSource = new SimpleDataSource("converted_arithmetic_projection");
    private final JdbcCompiler compiler = new JdbcCompiler(meta, new H2Dialect());
    private final JdbcExecutor executor = new JdbcExecutor(dataSource, meta, compiler);

    @BeforeEach
    void seed() throws SQLException, IOException {
        applySchema(dataSource);
        execute("create table if not exists converted_long_item (id bigint primary key, amount numeric(19,0))");
        execute("delete from item");
        execute("insert into item (id, name, score, score2, note, status) values"
                + " (1, 'banana', 10, 1, null, null),"
                + " (2, 'cherry', 20, 2, null, null),"
                + " (3, 'apple', 30, 3, null, null)");
        execute("delete from converted_long_item");
        execute("insert into converted_long_item (id, amount) values (1, 100), (2, 200)");
    }

    private QueryBuilder<SnapshotItem, JdbcQuery> items() {
        return new QueryBuilder<>(meta.entityType(SnapshotItem.class), compiler, executor);
    }

    private QueryBuilder<ConvertedLongItem, JdbcQuery> converted() {
        return new QueryBuilder<>(meta.entityType(ConvertedLongItem.class), compiler, executor);
    }

    // ------------------------------------------------- 披露的口径（现状即绿）

    /// `Long amount` 经 `AttributeConverter<Long, BigDecimal>` 落成 `numeric(19,0)`：`amount + 1` 的算值
    /// 运行期是 `BigDecimal`，而 `select(...)` 的静态类型是属性类型 `Long`——按 `Long` 取值会 `ClassCastException`。
    @Test
    void arithmeticOverConvertedColumnYieldsTheConverterDatabaseType() {
        List<Long> rows = converted()
                .select(Path.of(ConvertedLongItem::amount).add(1L))
                .orderBy(Path.of(ConvertedLongItem::id))
                .asc()
                .list();

        assertThat(rows).hasSize(2);
        assertThat(rows).first().isInstanceOf(BigDecimal.class);
        // 运行期是数据库类型、静态声明是属性类型：双重转型只为让编译期放行这次比较
        assertThat((BigDecimal) (Object) rows.get(0)).isEqualByComparingTo("101");
        assertThat((BigDecimal) (Object) rows.get(1)).isEqualByComparingTo("201");
    }

    // ------------------------------------------------------------ 对照组（缺口只在算术分支）

    /// 同列的路径投影已反转换（`ColumnReader#readCell`）：转换器与列映射本身没有问题。
    @Test
    void pathProjectionOfTheSameColumnIsTheAttributeType() {
        List<Long> rows = converted()
                .select(Path.of(ConvertedLongItem::amount))
                .orderBy(Path.of(ConvertedLongItem::id))
                .asc()
                .list();

        assertThat(rows).containsExactly(100L, 200L);
    }

    /// `max()` 的结果是列里的某个实际存储值，反转换安全（同 `docs/USAGE.md` §7「`max()` / `min()` 属性类型」）。
    @Test
    void maxOverConvertedColumnIsTheAttributeType() {
        assertThat(converted().select(Path.of(ConvertedLongItem::amount).max()).list())
                .containsExactly(200L);
    }

    /// 无转换器列的算术不受影响：结果仍是列的 Java 类型。
    @Test
    void arithmeticOverPlainColumnKeepsItsColumnType() {
        List<Integer> rows = items().select(Path.of(SnapshotItem::score).add(1))
                .orderBy(Path.of(SnapshotItem::id))
                .asc()
                .list();

        assertThat(rows).containsExactly(11, 21, 31);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void applySchema(DataSource dataSource) throws SQLException, IOException {
        String schema;
        try (InputStream stream = ConvertedArithmeticProjectionTest.class.getResourceAsStream("/schema.sql")) {
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

    /// 极简 DataSource：每次操作新连接。
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
