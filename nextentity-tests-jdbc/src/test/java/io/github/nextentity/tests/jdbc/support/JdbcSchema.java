package io.github.nextentity.tests.jdbc.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/// 建表脚本加载器：H2 与真 MySQL 共用 `src/test/resources/schema.sql`。
///
/// 逐句 `CREATE TABLE IF NOT EXISTS`：同一容器库被 31 个接线类串跑，DDL 必须幂等；
/// 不用 `DROP ALL OBJECTS`（H2 侧会与并行类互踩，且与 tests-jpa 的最小动作保持一致）。
public final class JdbcSchema {

    private JdbcSchema() {}

    public static void apply(Connection connection) {
        for (String statement : statements()) {
            try (Statement jdbc = connection.createStatement()) {
                jdbc.execute(statement);
            } catch (SQLException e) {
                throw new IllegalStateException("建表失败: " + statement, e);
            }
        }
    }

    private static String[] statements() {
        String script;
        try (InputStream in = JdbcSchema.class.getResourceAsStream("/schema.sql")) {
            if (in == null) {
                throw new IllegalStateException("classpath 缺少 /schema.sql");
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
