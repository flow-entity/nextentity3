package io.github.nextentity.jdbc.exec;

import io.github.nextentity.jdbc.meta.EnumStorage;
import io.github.nextentity.jdbc.meta.JdbcEntityAttribute;
import io.github.nextentity.jdbc.meta.JdbcTypes;
import jakarta.persistence.PersistenceException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;

/// 单列读取：按 `columnTypes` 里登记的静态 Java 类型从 `ResultSet` 收窄。
///
/// 类型收窄统一走 [JdbcTypes#read(ResultSet, int, Class)]（与写侧的类型绑定共用同一张类型清单）；
/// 枚举列的越界 ORDINAL / 脏 name 属于行数据问题，按设计 §3.12 / #24 包成 `PersistenceException`，
/// 文案带列名、原始值、枚举类型与项数（泄漏原生 `ArrayIndexOutOfBoundsException` 不算契约）。
final class ColumnReader {

    private static final String EXECUTION_FAILED = "SQL 执行失败: ";

    private ColumnReader() {}

    /// 读第 `index` 列（1 起）：`@Convert` 列先按转换器的数据库类型收窄再还原，枚举列按存储形态转成常量。
    static Object readCell(ResultSet rows, int index, Class<?> columnType, JdbcEntityAttribute attribute, String sql)
            throws SQLException {
        if (attribute != null && attribute.converter() != null) {
            Object value = JdbcTypes.read(rows, index, attribute.databaseType());
            return value == null ? null : attribute.converter().convertToEntityAttribute(value);
        }
        Object value = JdbcTypes.read(rows, index, columnType);
        if (value == null || attribute == null || attribute.enumStorage() == EnumStorage.NONE) {
            return value;
        }
        return enumValue(attribute, value, sql);
    }

    static Object readColumn(ResultSet rows, int index, Class<?> columnType) throws SQLException {
        return JdbcTypes.read(rows, index, columnType);
    }

    private static Object enumValue(JdbcEntityAttribute attribute, Object value, String sql) {
        // readCell 只在 enumStorage != NONE 时进入本方法，而枚举存储只会落在枚举类型的列上。
        Class<?> enumType = attribute.type();
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
