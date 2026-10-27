package io.github.nextentity.jdbc.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.GeneratedItem;
import jakarta.persistence.PersistenceException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/// B4：生成键读取必须兼容「单列键」与「驱动返回整行」两种形态（PG 返回 name, id；MSSQL/H2 单列）。
/// 设计 §5.4：统一 `RETURN_GENERATED_KEYS`，不按方言分支；整行时按 id 列名大小写不敏感定位。
class GeneratedKeysTest {

    private static final String ID = "id";
    private static final String NAME = "name";

    private final JdbcMeta meta = new JdbcMeta();

    @Test
    void readsSingleLongKey() {
        long id = insertedId(FakeKeys.resultSet(new String[] {ID}, new Object[] {42L}));

        assertThat(id).isEqualTo(42L);
    }

    @Test
    void readsSingleBigDecimalKey() {
        long id = insertedId(FakeKeys.resultSet(new String[] {ID}, new Object[] {new BigDecimal("43")}));

        assertThat(id).isEqualTo(43L);
    }

    @Test
    void locatesIdColumnWhenDriverReturnsWholeRow() {
        long id = insertedId(FakeKeys.resultSet(new String[] {NAME, ID}, new Object[] {"n", 44L}));

        assertThat(id).isEqualTo(44L);
    }

    @Test
    void locatesIdColumnCaseInsensitively() {
        long id = insertedId(FakeKeys.resultSet(new String[] {"ID", "NAME"}, new Object[] {45L, "n"}));

        assertThat(id).isEqualTo(45L);
    }

    @Test
    void fallsBackToFirstColumnWhenIdColumnMissingButReadable() {
        long id = insertedId(FakeKeys.resultSet(new String[] {"key", NAME}, new Object[] {46L, "n"}));

        assertThat(id).isEqualTo(46L);
    }

    @Test
    void reportsKeyColumnsWhenIdColumnMissingAndFirstColumnIsNotANumber() {
        ResultSet keys = FakeKeys.resultSet(new String[] {NAME, "other"}, new Object[] {"n", 99L});

        assertThatThrownBy(() -> insertedId(keys))
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("SQL 执行失败: ")
                .hasMessageContaining("未找到生成键列 id")
                .hasMessageContaining("实际列: name, other");
    }

    @Test
    void failsWhenDriverReturnsNoKeys() {
        ResultSet empty = FakeKeys.resultSet(new String[0], new Object[0]);

        assertThatThrownBy(() -> insertedId(empty))
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("未返回生成键: insert into generated_item");
    }

    private long insertedId(ResultSet keys) {
        JdbcPersistExecutor<GeneratedItem> persist =
                new JdbcPersistExecutor<>(FakeKeys.dataSource(keys), meta.entityType(GeneratedItem.class));
        GeneratedItem item = new GeneratedItem("n");
        persist.insert(item);
        return item.id();
    }

    /// 只实现生成键读取所需的 JDBC 形状（其余方法返回默认值）。
    private static final class FakeKeys {

        private FakeKeys() {}

        static ResultSet resultSet(String[] labels, Object[] values) {
            ResultSetMetaData metadata = metadata(labels);
            boolean[] consumed = {false};
            return proxy(ResultSet.class, (instance, method, arguments) -> switch (method.getName()) {
                case "next" -> !consumed[0] && (consumed[0] = true) && values.length > 0;
                case "getMetaData" -> metadata;
                case "getLong" -> readLong(values, (int) arguments[0] - 1);
                case "toString" -> "FakeKeys";
                default -> defaultValue(method);
            });
        }

        static DataSource dataSource(ResultSet keys) {
            return proxy(DataSource.class, (instance, method, arguments) -> switch (method.getName()) {
                case "getConnection" -> connection(keys);
                case "toString" -> "FakeKeysDataSource";
                default -> defaultValue(method);
            });
        }

        private static Connection connection(ResultSet keys) {
            return proxy(Connection.class, (instance, method, arguments) -> switch (method.getName()) {
                case "prepareStatement" -> statement(keys);
                case "toString" -> "FakeKeysConnection";
                default -> defaultValue(method);
            });
        }

        private static PreparedStatement statement(ResultSet keys) {
            return proxy(PreparedStatement.class, (instance, method, arguments) -> switch (method.getName()) {
                case "executeUpdate" -> 1;
                case "getGeneratedKeys" -> keys;
                case "toString" -> "FakeKeysStatement";
                default -> defaultValue(method);
            });
        }

        private static ResultSetMetaData metadata(String[] labels) {
            return proxy(ResultSetMetaData.class, (instance, method, arguments) -> switch (method.getName()) {
                case "getColumnCount" -> labels.length;
                case "getColumnLabel", "getColumnName" -> labels[(int) arguments[0] - 1];
                case "toString" -> "FakeKeysMetadata";
                default -> defaultValue(method);
            });
        }

        private static long readLong(Object[] values, int index) throws SQLException {
            if (index >= values.length) {
                throw new SQLException("列序号越界: " + (index + 1));
            }
            if (values[index] instanceof Number number) {
                return number.longValue();
            }
            throw new SQLException("无法把 " + values[index] + " 读成 long");
        }

        private static <T> T proxy(Class<T> type, InvocationHandler handler) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
        }

        private static Object defaultValue(Method method) {
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
                return "";
            }
            return null;
        }
    }
}
