package io.github.nextentity.jdbc.exec;

import io.github.nextentity.jdbc.meta.EnumStorage;
import io.github.nextentity.jdbc.meta.JdbcEntityAttribute;
import jakarta.persistence.PersistenceException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;

/// 单列读取：按 `columnTypes` 里登记的静态 Java 类型从 `ResultSet` 收窄，`null` 走 `wasNull`。
///
/// 枚举列的越界 ORDINAL / 脏 name 属于行数据问题，按设计 §3.12 / #24 包成 `PersistenceException`，
/// 文案带列名、原始值、枚举类型与项数（泄漏原生 `ArrayIndexOutOfBoundsException` 不算契约）。
final class ColumnReader {

    private static final String EXECUTION_FAILED = "SQL 执行失败: ";

    private ColumnReader() {}

    /// 读第 `index` 列（1 起）；`attribute` 非空且是枚举列时顺带按存储形态转成枚举常量。
    static Object readCell(ResultSet rows, int index, Class<?> columnType, JdbcEntityAttribute attribute, String sql)
            throws SQLException {
        Object value = readColumn(rows, index, columnType);
        if (value == null || attribute == null || attribute.enumStorage() == EnumStorage.NONE) {
            return value;
        }
        return enumValue(attribute, value, sql);
    }

    static Object readColumn(ResultSet rows, int index, Class<?> columnType) throws SQLException {
        if (columnType == Integer.class || columnType == int.class) {
            return intValue(rows, index);
        }
        if (columnType == Long.class || columnType == long.class) {
            return longValue(rows, index);
        }
        if (columnType == Double.class || columnType == double.class) {
            return doubleValue(rows, index);
        }
        if (columnType == String.class) {
            return rows.getString(index);
        }
        return rows.getObject(index);
    }

    private static Integer intValue(ResultSet rows, int index) throws SQLException {
        int value = rows.getInt(index);
        return rows.wasNull() ? null : value;
    }

    private static Long longValue(ResultSet rows, int index) throws SQLException {
        long value = rows.getLong(index);
        return rows.wasNull() ? null : value;
    }

    private static Double doubleValue(ResultSet rows, int index) throws SQLException {
        double value = rows.getDouble(index);
        return rows.wasNull() ? null : value;
    }

    private static Object enumValue(JdbcEntityAttribute attribute, Object value, String sql) {
        Class<?> enumType = attribute.type();
        if (!enumType.isEnum()) {
            return value;
        }
        Object[] constants = enumType.getEnumConstants();
        if (attribute.enumStorage() == EnumStorage.NAME) {
            return enumByName(value, enumType, attribute, constants, sql);
        }
        int ordinal = ((Number) value).intValue();
        if (ordinal < 0 || ordinal >= constants.length) {
            throw new PersistenceException(failure(sql, attribute, value, constants));
        }
        return constants[ordinal];
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumByName(
            Object value, Class<?> enumType, JdbcEntityAttribute attribute, Object[] constants, String sql) {
        try {
            return Enum.valueOf((Class) enumType, (String) value);
        } catch (IllegalArgumentException e) {
            throw new PersistenceException(failure(sql, attribute, value, constants), e);
        }
    }

    private static String failure(String sql, JdbcEntityAttribute attribute, Object value, Object[] constants) {
        return EXECUTION_FAILED + sql + "；列 " + attribute.column() + " 的值 " + value + " 无法解析为枚举 "
                + attribute.type().getName() + "（共 " + constants.length + " 项: "
                + Arrays.stream(constants).map(Object::toString).toList() + "）";
    }
}
