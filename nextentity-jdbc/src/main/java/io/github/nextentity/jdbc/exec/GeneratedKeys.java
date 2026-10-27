package io.github.nextentity.jdbc.exec;

import jakarta.persistence.PersistenceException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

/// 读取 `RETURN_GENERATED_KEYS` 结果集：驱动返回单列时直接读；返回整行时按 id 列名定位（PG 会返回整行）。
/// 不按方言分支，列名比较大小写不敏感。
final class GeneratedKeys {

    private static final String EXECUTION_FAILED = "SQL 执行失败: ";

    private GeneratedKeys() {}

    /// 读出生成键的第一行第一列（调用方已确认 `keys.next()` 为真）。
    static long read(ResultSet keys, String idColumn, String sql) throws SQLException {
        ResultSetMetaData metadata = keys.getMetaData();
        if (metadata.getColumnCount() == 1) {
            return keys.getLong(1);
        }
        int index = index(metadata, idColumn);
        if (index > 0) {
            return keys.getLong(index);
        }
        return fallback(keys, metadata, idColumn, sql);
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

    private static long fallback(ResultSet keys, ResultSetMetaData metadata, String idColumn, String sql) {
        try {
            return keys.getLong(1);
        } catch (SQLException e) {
            throw new PersistenceException(
                    EXECUTION_FAILED + sql + "；未找到生成键列 " + idColumn + "，实际列: " + labels(metadata), e);
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
