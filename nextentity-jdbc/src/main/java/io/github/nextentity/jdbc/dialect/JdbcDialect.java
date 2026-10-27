package io.github.nextentity.jdbc.dialect;

/// JDBC 后端方言接缝。
///
/// 本期 MySQL 8 与 H2（MODE=MySQL）在 SQL 语法上完全对齐（`limit ? offset ?`、`for update`、
/// `count(*)`、`left join` 均共同支持），因此这里只有产品名探测，没有渲染分叉方法。
public interface JdbcDialect {

    /// 方言名，用于诊断：`"mysql"` / `"h2"`。
    ///
    /// @return 方言名
    String name();

    /// 按数据库产品名取方言实现（大小写无关）。
    ///
    /// @param databaseProductName `DatabaseMetaData#getDatabaseProductName()`，可为 null
    /// @return 对应方言
    /// @throws UnsupportedOperationException 产品名不受支持
    static JdbcDialect of(String databaseProductName) {
        if ("MySQL".equalsIgnoreCase(databaseProductName)) {
            return new MySqlDialect();
        }
        if ("H2".equalsIgnoreCase(databaseProductName)) {
            return new H2Dialect();
        }
        throw new UnsupportedOperationException("不支持的数据库产品: " + databaseProductName + "（本期只支持 MySQL / H2）");
    }
}
