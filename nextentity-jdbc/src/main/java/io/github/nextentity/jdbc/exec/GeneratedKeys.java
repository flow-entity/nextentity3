package io.github.nextentity.jdbc.exec;

import jakarta.persistence.PersistenceException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

/// 读取 `RETURN_GENERATED_KEYS` 结果集：驱动返回单列时直接读；返回整行时按 id 列名定位（PG 会返回整行）。
/// 不按方言分支，列名比较大小写不敏感。
///
/// 3.1.0 起生成键类型放宽到 JPA 允许的数值型（`short` / `int` / `long` / `BigInteger`，含包装），
/// 统一按 [BigDecimal] 读出再做精确收窄，超出目标类型范围时抛 `PersistenceException` 而不是静默截断。
final class GeneratedKeys {

    private static final String EXECUTION_FAILED = "SQL 执行失败: ";

    private GeneratedKeys() {}

    /// 读出生成键的第一行（调用方已确认 `keys.next()` 为真），并按 `idType` 转成目标类型。
    static Object read(ResultSet keys, String idColumn, Class<?> idType, String sql) throws SQLException {
        ResultSetMetaData metadata = keys.getMetaData();
        int index = metadata.getColumnCount() == 1 ? 1 : index(metadata, idColumn);
        if (index == 0) {
            index = 1;
        }
        return value(keys, index, idType, idColumn, metadata, sql);
    }

    private static int index(ResultSetMetaData metadata, String column) throws SQLException {
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            if (column.equalsIgnoreCase(metadata.getColumnLabel(i))
                    || column.equalsIgnoreCase(metadata.getColumnName(i))) {
                return i;
            }
        }
        return 0;
    }

    @SuppressWarnings("PMD.CyclomaticComplexity") // 收窄分支由 JPA 允许的生成键类型集（short/int/long/BigInteger）决定
    private static Object value(
            ResultSet keys, int index, Class<?> idType, String idColumn, ResultSetMetaData metadata, String sql) {
        BigDecimal value;
        try {
            value = keys.getBigDecimal(index);
        } catch (SQLException e) {
            throw new PersistenceException(
                    EXECUTION_FAILED + sql + "；未找到生成键列 " + idColumn + "，实际列: " + labels(metadata), e);
        }
        if (value == null) {
            throw new PersistenceException("生成键为 null: " + sql);
        }
        try {
            if (idType == BigInteger.class) {
                return value.toBigIntegerExact();
            }
            if (idType == short.class || idType == Short.class) {
                return value.shortValueExact();
            }
            if (idType == int.class || idType == Integer.class) {
                return value.intValueExact();
            }
            return value.longValueExact();
        } catch (ArithmeticException e) {
            throw new PersistenceException("生成键 " + value + " 超出 " + idType.getName() + " 的范围: " + sql, e);
        }
    }

    private static String labels(ResultSetMetaData metadata) {
        StringBuilder result = new StringBuilder();
        try {
            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                if (i > 1) {
                    result.append(", ");
                }
                result.append(metadata.getColumnLabel(i));
            }
        } catch (SQLException e) {
            return "未知";
        }
        return result.toString();
    }
}
