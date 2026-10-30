package io.github.nextentity.jdbc.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.jdbc.support.ConvertedItem;
import io.github.nextentity.jdbc.support.ItemStatus;
import io.github.nextentity.jdbc.support.NumbersItem;
import io.github.nextentity.jdbc.support.OrdinalItem;
import io.github.nextentity.jdbc.support.SnapshotItem;
import io.github.nextentity.jdbc.support.TimesItem;
import io.github.nextentity.jdbc.support.VersionedItem;
import io.github.nextentity.jdbc.support.VersionedShortItem;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/// 列类型接缝的双绑证据：白名单（`supports`）与两个方向的值转换在同一个类里，逐类型各走一遍——
/// 写侧 `toJdbc` 的绑定形态、读侧 `read` 的还原形态（H2 真驱动，不用 mock）。
class JdbcTypesTest {

    private static final String URL = "jdbc:h2:mem:jdbc_types;MODE=MySQL;DB_CLOSE_DELAY=-1";

    private final JdbcMeta meta = new JdbcMeta();

    @Test
    void supportsEveryExtendedColumnType() {
        List<Class<?>> supported = List.of(
                String.class,
                Integer.class,
                int.class,
                Long.class,
                long.class,
                Short.class,
                short.class,
                Double.class,
                double.class,
                Float.class,
                float.class,
                Boolean.class,
                boolean.class,
                BigDecimal.class,
                BigInteger.class,
                LocalDate.class,
                LocalDateTime.class,
                LocalTime.class,
                Instant.class,
                Date.class,
                Time.class,
                Timestamp.class);

        assertThat(supported).allMatch(JdbcTypes::supports);
        assertThat(JdbcTypes.supports(ItemStatus.class)).isTrue();
        assertThat(JdbcTypes.supports(byte.class)).isFalse();
        assertThat(JdbcTypes.supports(Byte.class)).isFalse();
        assertThat(JdbcTypes.supports(char.class)).isFalse();
        assertThat(JdbcTypes.supports(Character.class)).isFalse();
        assertThat(JdbcTypes.supports(byte[].class)).isFalse();
    }

    @Test
    void toJdbcBindsJavaTimeAndBigIntegerInDriverFriendlyShape() {
        JdbcEntityAttribute timeAttribute = attribute(TimesItem.class, "createdAt");
        JdbcEntityAttribute numberAttribute = attribute(NumbersItem.class, "serial");
        LocalDate date = LocalDate.of(1990, 1, 2);
        LocalDateTime dateTime = LocalDateTime.of(2024, 3, 4, 5, 6, 7);
        LocalTime time = LocalTime.of(6, 30);
        Instant instant = Instant.parse("2024-01-02T03:04:05Z");

        assertThat(JdbcTypes.toJdbc(date, timeAttribute)).isEqualTo(Date.valueOf(date));
        assertThat(JdbcTypes.toJdbc(dateTime, timeAttribute)).isEqualTo(Timestamp.valueOf(dateTime));
        assertThat(JdbcTypes.toJdbc(time, timeAttribute)).isEqualTo(Time.valueOf(time));
        assertThat(JdbcTypes.toJdbc(instant, timeAttribute)).isEqualTo(Timestamp.from(instant));
        assertThat(JdbcTypes.toJdbc(new BigInteger("12"), numberAttribute)).isEqualTo(new BigDecimal("12"));
        assertThat(JdbcTypes.toJdbc(42, numberAttribute)).isEqualTo(42);
        assertThat(JdbcTypes.toJdbc("text", numberAttribute)).isEqualTo("text");
    }

    @Test
    void toJdbcStoresEnumByNameOrOrdinal() {
        JdbcEntityAttribute named = attribute(SnapshotItem.class, "status");
        JdbcEntityAttribute ordinal = attribute(OrdinalItem.class, "status");

        assertThat(JdbcTypes.toJdbc(ItemStatus.DONE, named)).isEqualTo("DONE");
        assertThat(JdbcTypes.toJdbc(ItemStatus.DONE, ordinal)).isEqualTo(ItemStatus.DONE.ordinal());
    }

    @Test
    void toJdbcWritesZeroForNullVersion() {
        JdbcEntityAttribute longVersion = attribute(VersionedItem.class, "version");
        JdbcEntityAttribute shortVersion = attribute(VersionedShortItem.class, "version");

        assertThat(JdbcTypes.toJdbc(null, longVersion)).isEqualTo(0L);
        assertThat(JdbcTypes.toJdbc(null, shortVersion)).isEqualTo((short) 0);
        assertThat(JdbcTypes.toJdbc(null, attribute(SnapshotItem.class, "name")))
                .isNull();
    }

    @Test
    void toJdbcUsesConverterAndLiteralSkipsVersionZero() {
        JdbcEntityAttribute converted = attribute(ConvertedItem.class, "status");
        JdbcEntityAttribute longVersion = attribute(VersionedItem.class, "version");

        assertThat(JdbcTypes.toJdbc(ItemStatus.DONE, converted)).isEqualTo("DONE");
        assertThat(JdbcTypes.toLiteral(ItemStatus.DONE, converted)).isEqualTo("DONE");
        assertThat(JdbcTypes.toLiteral(null, longVersion)).isNull();
    }

    @Test
    void nextVersionCoversEveryNumericVersionType() {
        assertThat(JdbcTypes.nextVersion(null, Integer.class)).isEqualTo(0);
        assertThat(JdbcTypes.nextVersion(2, Integer.class)).isEqualTo(3);
        assertThat(JdbcTypes.nextVersion(null, Long.class)).isEqualTo(0L);
        assertThat(JdbcTypes.nextVersion(2L, Long.class)).isEqualTo(3L);
        assertThat(JdbcTypes.nextVersion(null, Short.class)).isEqualTo((short) 0);
        assertThat(JdbcTypes.nextVersion((short) 2, Short.class)).isEqualTo((short) 3);
        assertThat(JdbcTypes.nextVersion(null, int.class)).isEqualTo(0);
        assertThat(JdbcTypes.nextVersion(2, int.class)).isEqualTo(3);
        assertThat(JdbcTypes.nextVersion(null, long.class)).isEqualTo(0L);
        assertThat(JdbcTypes.nextVersion(2L, long.class)).isEqualTo(3L);
        assertThat(JdbcTypes.nextVersion(null, short.class)).isEqualTo((short) 0);
        assertThat(JdbcTypes.nextVersion((short) 2, short.class)).isEqualTo((short) 3);
        assertThatThrownBy(() -> JdbcTypes.nextVersion(null, Instant.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(JdbcEntityRules.VERSION_UNSUPPORTED + Instant.class.getName());
    }

    @Test
    void readRestoresEveryColumnTypeFromRealDriver() throws SQLException {
        List<Case> cases = List.of(
                new Case(String.class, "varchar(50)", "text"),
                new Case(Integer.class, "int", 7),
                new Case(int.class, "int", 7),
                new Case(Long.class, "bigint", 7L),
                new Case(long.class, "bigint", 7L),
                new Case(Short.class, "smallint", (short) 7),
                new Case(short.class, "smallint", (short) 7),
                new Case(Double.class, "double", 1.5d),
                new Case(double.class, "double", 1.5d),
                new Case(Float.class, "real", 1.5f),
                new Case(float.class, "real", 1.5f),
                new Case(Boolean.class, "boolean", true),
                new Case(boolean.class, "boolean", true),
                new Case(BigDecimal.class, "decimal(10, 2)", new BigDecimal("12.34")),
                new Case(BigInteger.class, "decimal(18, 0)", new BigInteger("123")),
                new Case(LocalDate.class, "date", LocalDate.of(1990, 1, 2)),
                new Case(LocalDateTime.class, "timestamp", LocalDateTime.of(2024, 3, 4, 5, 6, 7)),
                new Case(LocalTime.class, "time", LocalTime.of(6, 30, 0)),
                new Case(Instant.class, "timestamp", Instant.parse("2024-01-02T03:04:05Z")),
                new Case(Date.class, "date", Date.valueOf(LocalDate.of(2020, 5, 6))),
                new Case(Time.class, "time", Time.valueOf(LocalTime.of(7, 8, 9))),
                new Case(Timestamp.class, "timestamp", Timestamp.valueOf(LocalDateTime.of(2021, 6, 7, 8, 9, 10))));

        JdbcEntityAttribute attribute = attribute(SnapshotItem.class, "name");
        try (Connection connection = DriverManager.getConnection(URL)) {
            for (int index = 0; index < cases.size(); index++) {
                Case testCase = cases.get(index);
                String table = "types_" + index;
                assertRoundTrip(connection, table, testCase, attribute);
            }
        }
    }

    private static void assertRoundTrip(
            Connection connection, String table, Case testCase, JdbcEntityAttribute attribute) throws SQLException {
        try (Statement ddl = connection.createStatement()) {
            ddl.execute("create table " + table + " (v " + testCase.sqlType() + ")");
        }
        String sql = "insert into " + table + " (v) values (?)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setObject(1, JdbcTypes.toJdbc(testCase.value(), attribute));
            insert.executeUpdate();
        }
        try (Statement select = connection.createStatement();
                ResultSet rows = select.executeQuery("select v from " + table)) {
            assertThat(rows.next()).isTrue();
            assertThat(JdbcTypes.read(rows, 1, testCase.type())).isEqualTo(testCase.value());
        }
    }

    private JdbcEntityAttribute attribute(Class<?> type, String name) {
        JdbcEntityAttribute attribute = meta.entityType(type).attribute(name);
        assertThat(attribute).isNotNull();
        return attribute;
    }

    private record Case(Class<?> type, String sqlType, Object value) {}

    @Test
    void toLiteralKeepsSqlNullAwayFromEnumColumns() {
        JdbcEntityAttribute status = meta.entityType(SnapshotItem.class).attribute("status");

        assertThat(JdbcTypes.toLiteral(null, status)).isNull();
    }
}
