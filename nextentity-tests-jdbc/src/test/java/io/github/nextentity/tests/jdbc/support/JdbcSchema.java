package io.github.nextentity.tests.jdbc.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/// 建表脚本加载器：H2 与真 MySQL 共用 `schema-mysql.sql`，PG/MSSQL 用各自脚本。
///
/// 按 `;` 切分逐句执行：同一容器库被多个接线类串跑，DDL 必须幂等（MySQL 用
/// `create table if not exists`；PG/MSSQL 每类独立 schema/database，用 `drop table if exists` + `create table`）。
public final class JdbcSchema {

    /// H2（`MODE=MySQL`）与真 MySQL 共用的建表脚本。
    public static final String MYSQL = "schema-mysql.sql";

    /// PostgreSQL 16 建表脚本。
    public static final String POSTGRES = "schema-postgres.sql";

    /// SQL Server 2022 建表脚本。
    public static final String SQLSERVER = "schema-sqlserver.sql";

    private JdbcSchema() {}

    /// 执行 H2/MySQL 建表脚本（`schema-mysql.sql`）。
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
