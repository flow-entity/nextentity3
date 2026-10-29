package io.github.nextentity.tests.jdbc.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/// 建表脚本加载器：H2 用 `schema-h2.sql`、真 MySQL 用 `schema-mysql.sql`（两者内容相同，唯一差异是
/// 保留字表 `order` 的引用字符——H2 与 Hibernate 一样用双引号，MySQL 用反引号），PG/MSSQL 用各自脚本。
///
/// 按 `;` 切分逐句执行：同一容器库被多个接线类串跑，DDL 必须幂等（MySQL/H2 用
/// `create table if not exists`；PG/MSSQL 每类独立 schema/database，用 `drop table if exists` + `create table`）。
public final class JdbcSchema {

    /// H2（`MODE=MySQL`）的建表脚本：保留字用双引号，与 Hibernate 在 H2 上的形态一致。
    public static final String H2 = "schema-h2.sql";

    /// 真 MySQL 的建表脚本：保留字用反引号。
    public static final String MYSQL = "schema-mysql.sql";

    /// PostgreSQL 16 建表脚本。
    public static final String POSTGRES = "schema-postgres.sql";

    /// SQL Server 2022 建表脚本。
    public static final String SQLSERVER = "schema-sqlserver.sql";

    private JdbcSchema() {}

    /// 执行真 MySQL 建表脚本（`schema-mysql.sql`）。
    public static void apply(Connection connection) {
        apply(connection, MYSQL);
    }

    /// 执行 classpath 下 `/<resource>` 建表脚本。
    ///
    /// @param connection 目标连接（已切到目标库/schema）
    /// @param resource   classpath 资源名，如 `schema-postgres.sql`
    public static void apply(Connection connection, String resource) {
        for (String statement : statements(resource)) {
            try (Statement jdbc = connection.createStatement()) {
                jdbc.execute(statement);
            } catch (SQLException e) {
                throw new IllegalStateException("建表失败(" + resource + "): " + statement, e);
            }
        }
    }

    private static String[] statements(String resource) {
        String script;
        try (InputStream in = JdbcSchema.class.getResourceAsStream("/" + resource)) {
            if (in == null) {
                throw new IllegalStateException("classpath 缺少 /" + resource);
            }
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return script.lines()
                .map(String::strip)
                .filter(line -> !line.startsWith("--"))
                .reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right)
                .split(";");
    }
}
