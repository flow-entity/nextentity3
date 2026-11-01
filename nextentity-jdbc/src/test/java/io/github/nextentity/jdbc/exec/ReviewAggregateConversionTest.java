package io.github.nextentity.jdbc.exec;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.api.model.Tuple2;
import io.github.nextentity.core.builder.QueryBuilder;
import io.github.nextentity.jdbc.compile.JdbcCompiler;
import io.github.nextentity.jdbc.compile.JdbcQuery;
import io.github.nextentity.jdbc.dialect.H2Dialect;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.ItemStatus;
import io.github.nextentity.jdbc.support.ItemStatusConverter;
import io.github.nextentity.jdbc.support.SnapshotItem;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// 评审复现（R1 独立验证 R3 的 F-1）：`max()` / `min()` 的结果是**属性类型**，JDBC 读侧必须与
/// 路径投影走同一套反转换（枚举存储形态 → 常量、`@Convert` 的数据库形态 → 属性值）。
///
/// 契约：`docs/USAGE.md` §7 聚合结果表「`max()` / `min()` ｜ 属性类型」，
/// `api.Aggregates#max()` 的 `@param <U> 被聚合的属性值类型`。
///
/// 语义上 `max` / `min` 的反转换是**安全**的：结果是该列的某一个实际存储值，把它喂给
/// `convertToEntityAttribute` 与普通路径投影读回同一列完全同域（不像 `sum` 那样要求转换器保值）。
/// `count` / `avg` 的结果域与列无关（`Long` / `Double`），不得反转换——本类留了对照组钉住这一点。
class ReviewAggregateConversionTest {

    private final JdbcMeta meta = new JdbcMeta();
    private final DataSource dataSource = new SimpleDataSource("review_aggregate_conversion");
    private final JdbcCompiler compiler = new JdbcCompiler(meta, new H2Dialect());
    private final JdbcExecutor executor = new JdbcExecutor(dataSource, meta, compiler);

    @BeforeEach
    void seed() throws SQLException, IOException {
        applySchema(dataSource);
        execute("create table if not exists converted_aggregate_item (id bigint primary key, status varchar(32))");
        execute("delete from item");
        execute("insert into item (id, name, score, score2, note, status) values"
                + " (1, 'banana', 10, 1, 'n1', 'ACTIVE'),"
                + " (2, 'cherry', 20, 2, null, null),"
                + " (3, 'apple', 30, 3, 'n3', 'DONE')");
        execute("delete from converted_aggregate_item");
        execute("insert into converted_aggregate_item (id, status) values (1, 'ACTIVE'), (2, 'DONE')");
    }

    private QueryBuilder<SnapshotItem, JdbcQuery> items() {
        return new QueryBuilder<>(meta.entityType(SnapshotItem.class), compiler, executor);
    }

    private QueryBuilder<ConvertedAggregateItem, JdbcQuery> converted() {
        return new QueryBuilder<>(meta.entityType(ConvertedAggregateItem.class), compiler, executor);
    }

    // ---------------------------------------------------------- 缺陷本体（RED）

    @Test
    void maxOverEnumeratedStringColumnYieldsTheEnumConstant() {
        List<ItemStatus> max =
                items().select(Path.of(SnapshotItem::status).max()).list();

        // 列里存的是 'ACTIVE' / null / 'DONE'：max 是 'DONE'，但结果类型是属性类型 ItemStatus
        assertThat(max).containsExactly(ItemStatus.DONE);
    }

    @Test
    void minOverConvertedColumnYieldsTheConvertedValue() {
        List<ItemStatus> min = converted()
                .select(Path.of(ConvertedAggregateItem::status).min())
                .list();

        // 库里存的是 'ACTIVE' / 'DONE'（转换器的数据库形态）：min 读回必须是 ItemStatus，不是 String
        assertThat(min).containsExactly(ItemStatus.ACTIVE);
    }

    @Test
    void groupedAggregateConvertsTheAggregatedColumn() {
        List<Tuple2<Long, ItemStatus>> rows = items().select(
                        Path.of(SnapshotItem::id), Path.of(SnapshotItem::status).max())
                .groupBy(SnapshotItem::id)
                .orderBy(Path.of(SnapshotItem::id))
                .asc()
                .list();

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).get1()).isEqualTo(ItemStatus.ACTIVE);
        assertThat(rows.get(1).get1()).isNull();
        assertThat(rows.get(2).get1()).isEqualTo(ItemStatus.DONE);
    }

    // ------------------------------------------------------------ 对照组（GREEN）

    /// 缺口只在聚合分支：同样两列的**路径投影**已经反转换（`RowReader#enumAttribute` 的
    /// `PathProjection` 分支 + `ColumnReader#readCell`），所以列映射与转换器本身没有问题。
    @Test
    void pathProjectionOfTheSameColumnsIsConverted() {
        assertThat(items().select(Path.of(SnapshotItem::status))
                        .orderBy(Path.of(SnapshotItem::id))
                        .asc()
                        .list())
                .containsExactly(ItemStatus.ACTIVE, null, ItemStatus.DONE);

        assertThat(converted()
                        .select(Path.of(ConvertedAggregateItem::status))
                        .orderBy(Path.of(ConvertedAggregateItem::id))
                        .asc()
                        .list())
                .containsExactly(ItemStatus.ACTIVE, ItemStatus.DONE);
    }

    /// `count` / `avg` 的结果域与列值域无关（`Long` / `Double`），**不得**跟着反转换：
    /// 对枚举列 `count` 套 `Enum.valueOf` 会炸，对 `@Convert` 列按数据库类型收窄会把 `Long` 读错。
    @Test
    void countAndAvgAreNotConverted() {
        assertThat(items().select(Path.of(SnapshotItem::status).count()).list()).containsExactly(2L);
        assertThat(items().select(Path.of(SnapshotItem::score).avg()).list()).containsExactly(20.0);
        assertThat(converted()
                        .select(Path.of(ConvertedAggregateItem::status).count())
                        .list())
                .containsExactly(2L);
    }

    /// 普通（无枚举存储、无转换器）列的 `max` / `min` 不受影响：仍是列的 Java 类型。
    @Test
    void maxAndMinOnPlainColumnsKeepTheirColumnType() {
        assertThat(items().select(Path.of(SnapshotItem::score).max()).list()).containsExactly(30);
        assertThat(items().select(Path.of(SnapshotItem::score).min()).list()).containsExactly(10);
    }

    // ------------------------------------------------------------------ fixture

    /// `@Convert` 落在 String 列上：数据库形态与属性形态不同型，读侧不反转换就会拿到 `String`。
    @Entity
    @Table(name = "converted_aggregate_item")
    static class ConvertedAggregateItem {

        @Id
        private Long id;

        @Convert(converter = ItemStatusConverter.class)
        private ItemStatus status;

        protected ConvertedAggregateItem() {}

        Long id() {
            return id;
        }

        ItemStatus status() {
            return status;
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void applySchema(DataSource dataSource) throws SQLException, IOException {
        String schema;
        try (InputStream stream = ReviewAggregateConversionTest.class.getResourceAsStream("/schema.sql")) {
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
