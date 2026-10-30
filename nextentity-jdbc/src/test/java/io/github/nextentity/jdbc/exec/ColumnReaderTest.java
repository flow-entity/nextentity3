package io.github.nextentity.jdbc.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.jdbc.meta.JdbcEntityAttribute;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.ConvertedItem;
import io.github.nextentity.jdbc.support.ConvertedLongItem;
import io.github.nextentity.jdbc.support.ItemStatus;
import io.github.nextentity.jdbc.support.OrdinalItem;
import io.github.nextentity.jdbc.support.SnapshotItem;
import io.github.nextentity.jdbc.support.StrictConvertedItem;
import jakarta.persistence.PersistenceException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/// `ColumnReader` 的静态类型收窄（设计 §5.1 / §5.2）：Integer / int / Long / long / Double / double / String /
/// `getObject` 回退、NULL 走 `wasNull`、枚举两种存储形态与两种脏数据（#26）。
class ColumnReaderTest {

    private static final String URL = "jdbc:h2:mem:column_reader;MODE=MySQL;DB_CLOSE_DELAY=-1";

    private final JdbcMeta meta = new JdbcMeta();

    @Test
    void narrowsEveryWhitelistedScalarType() throws SQLException {
        withRow("select 5, 7, 1.5, 'abc', 5, 7, 1.5 from dual", rows -> {
            assertThat(ColumnReader.readCell(rows, 1, Integer.class, null, "")).isEqualTo(5);
            assertThat(ColumnReader.readCell(rows, 2, Long.class, null, "")).isEqualTo(7L);
            assertThat(ColumnReader.readCell(rows, 3, Double.class, null, "")).isEqualTo(1.5);
            assertThat(ColumnReader.readCell(rows, 4, String.class, null, "")).isEqualTo("abc");
            assertThat(ColumnReader.readCell(rows, 5, int.class, null, "")).isEqualTo(5);
            assertThat(ColumnReader.readCell(rows, 6, long.class, null, "")).isEqualTo(7L);
            assertThat(ColumnReader.readCell(rows, 7, double.class, null, "")).isEqualTo(1.5);
        });
    }

    @Test
    void readsNullAsNullForEveryType() throws SQLException {
        String sql = "select cast(null as int), cast(null as bigint), cast(null as double), "
                + "cast(null as varchar), cast(null as int), cast(null as bigint) from dual";
        withRow(sql, rows -> {
            assertThat(ColumnReader.readCell(rows, 1, Integer.class, null, sql)).isNull();
            assertThat(ColumnReader.readCell(rows, 2, Long.class, null, sql)).isNull();
            assertThat(ColumnReader.readCell(rows, 3, Double.class, null, sql)).isNull();
            assertThat(ColumnReader.readCell(rows, 4, String.class, null, sql)).isNull();
            assertThat(ColumnReader.readCell(rows, 5, int.class, null, sql)).isNull();
            assertThat(ColumnReader.readCell(rows, 6, long.class, null, sql)).isNull();
        });
    }

    @Test
    void fallsBackToGetObjectForOtherTypes() throws SQLException {
        withRow("select 42, 'x' from dual", rows -> {
            assertThat(ColumnReader.readCell(rows, 1, Object.class, null, "")).isEqualTo(42);
            assertThat(ColumnReader.readCell(rows, 2, Number.class, null, "")).isEqualTo("x");
        });
    }

    @Test
    void convertsStringEnumByName() throws SQLException {
        JdbcEntityAttribute status = meta.entityType(SnapshotItem.class).attribute("status");

        withRow("select 'ACTIVE', cast(null as varchar) from dual", rows -> {
            assertThat(ColumnReader.readCell(rows, 1, ItemStatus.class, status, ""))
                    .isEqualTo(ItemStatus.ACTIVE);
            assertThat(ColumnReader.readCell(rows, 2, ItemStatus.class, status, ""))
                    .isNull();
        });
    }

    @Test
    void rejectsDirtyEnumName() throws SQLException {
        JdbcEntityAttribute status = meta.entityType(SnapshotItem.class).attribute("status");
        String sql = "select 'BOGUS' from dual";

        withRow(sql, rows -> assertThatThrownBy(() -> ColumnReader.readCell(rows, 1, ItemStatus.class, status, sql))
                .isInstanceOf(PersistenceException.class)
                .hasMessage("SQL 执行失败: " + sql + "；列 status 的值 BOGUS 无法解析为枚举 " + ItemStatus.class.getName()
                        + "（共 3 项: [NEW, ACTIVE, DONE]）")
                .hasCauseInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void convertsOrdinalEnumByIndex() throws SQLException {
        JdbcEntityAttribute status = meta.entityType(OrdinalItem.class).attribute("status");
        ItemStatus[] constants = ItemStatus.values();

        withRow("select 0, 1, 2 from dual", rows -> {
            assertThat(ColumnReader.readCell(rows, 1, Integer.class, status, ""))
                    .isEqualTo(constants[0]);
            assertThat(ColumnReader.readCell(rows, 2, Integer.class, status, ""))
                    .isEqualTo(constants[1]);
            assertThat(ColumnReader.readCell(rows, 3, Integer.class, status, ""))
                    .isEqualTo(constants[2]);
        });
    }

    @Test
    void rejectsOutOfRangeOrdinal() throws SQLException {
        JdbcEntityAttribute status = meta.entityType(OrdinalItem.class).attribute("status");
        String sql = "select 99, -1 from dual";

        withRow(sql, rows -> {
            assertThatThrownBy(() -> ColumnReader.readCell(rows, 1, Integer.class, status, sql))
                    .isInstanceOf(PersistenceException.class)
                    .hasMessageContaining("SQL 执行失败: " + sql)
                    .hasMessageContaining("列 status")
                    .hasMessageContaining("值 99");
            assertThatThrownBy(() -> ColumnReader.readCell(rows, 2, Integer.class, status, sql))
                    .isInstanceOf(PersistenceException.class)
                    .hasMessageContaining("值 -1");
        });
    }

    @Test
    void keepsRawValueWhenAttributeIsNotAnEnumColumn() throws SQLException {
        JdbcEntityAttribute name = meta.entityType(SnapshotItem.class).attribute("name");

        withRow("select 'abc' from dual", rows -> {
            assertThat(ColumnReader.readCell(rows, 1, String.class, name, "")).isEqualTo("abc");
            assertThat(ColumnReader.readCell(rows, 1, String.class, null, "")).isEqualTo("abc");
        });
    }

    @Test
    void rejectsOrdinalAtTheUpperBoundWithFullMessage() throws SQLException {
        JdbcEntityAttribute status = meta.entityType(OrdinalItem.class).attribute("status");
        String sql = "select 3 from dual";

        withRow(sql, rows -> assertThatThrownBy(() -> ColumnReader.readCell(rows, 1, Integer.class, status, sql))
                .isInstanceOf(PersistenceException.class)
                .hasMessage("SQL 执行失败: " + sql + "；列 status 的值 3 无法解析为枚举 " + ItemStatus.class.getName()
                        + "（共 3 项: [NEW, ACTIVE, DONE]）"));
    }

    @Test
    void readsConvertedColumnThroughAttributeConverter() throws SQLException {
        JdbcEntityAttribute status = meta.entityType(ConvertedItem.class).attribute("status");

        withRow("select 'ACTIVE', cast(null as varchar) from dual", rows -> {
            assertThat(ColumnReader.readCell(rows, 1, String.class, status, "")).isEqualTo(ItemStatus.ACTIVE);
            assertThat(ColumnReader.readCell(rows, 2, String.class, status, "")).isNull();
        });
    }

    @Test
    void readsConverterColumnByItsDatabaseType() throws SQLException {
        JdbcEntityAttribute amount = meta.entityType(ConvertedLongItem.class).attribute("amount");

        withRow("select cast(42 as bigint) from dual", rows -> assertThat(
                        ColumnReader.readCell(rows, 1, Long.class, amount, "select amount"))
                .isEqualTo(42L));
    }

    @Test
    void readColumnKeepsTheStaticTypeWithoutAttribute() throws SQLException {
        withRow("select 7 from dual", rows -> assertThat(ColumnReader.readColumn(rows, 1, Long.class))
                .isEqualTo(7L));
    }

    @Test
    void converterIsNotCalledForSqlNull() throws SQLException {
        JdbcEntityAttribute status = meta.entityType(StrictConvertedItem.class).attribute("status");

        withRow("select 'ACTIVE', cast(null as varchar) from dual", rows -> {
            assertThat(ColumnReader.readCell(rows, 1, String.class, status, "")).isEqualTo(ItemStatus.ACTIVE);
            assertThat(ColumnReader.readCell(rows, 2, String.class, status, "")).isNull();
        });
    }

    private static void withRow(String sql, RowAssertion assertion) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, "sa", "");
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            assertion.accept(rows);
        }
    }

    @FunctionalInterface
    private interface RowAssertion {

        void accept(ResultSet rows) throws SQLException;
    }
}
