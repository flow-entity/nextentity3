package io.github.nextentity.tests.jdbc.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/// L4 的 PostgreSQL 容器：一个 JVM 起一个实例，每个接线类一个独立 **schema**（库级隔离）。
///
/// 隔离用 schema 而不是 database：PG 里 `database` 不能在同一连接内切换，31 个类若各建库就得
/// 各开连接、各自建表，启动时间翻倍；而 `create schema if not exists` + `setSchema` 足够互不干扰。
public final class PostgresL4Container extends L4Container<PostgreSQLContainer<?>> {

    private static final String IMAGE_PROPERTY = "nextentity.postgres.image";

    private static final String DEFAULT_IMAGE = "docker.xuanyuan.run/library/postgres:16";

    private static final PostgresL4Container INSTANCE = new PostgresL4Container();

    private PostgresL4Container() {
        super(new PostgreSQLContainer<>(DockerImageName.parse(imageOf(IMAGE_PROPERTY, DEFAULT_IMAGE))
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("nextentity_jdbc_pg")
                .withUsername("test")
                .withPassword("test")
                .withStartupTimeout(Duration.ofMinutes(3)));
        container().start();
    }

    /// 打开一条连到指定 schema 的连接并建好表；schema 名由接线类给（小写类名）。
    ///
    /// @param schema 本类专用的 schema 名
    /// @return 单连接数据源（调用方在 `@AfterAll` 关闭）
    public static SingleConnectionDataSource dataSource(String schema) {
        Connection connection = INSTANCE.openConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("create schema if not exists " + schema);
        } catch (SQLException e) {
            throw new IllegalStateException("创建 schema 失败: " + schema, e);
        }
        try {
            connection.setSchema(schema);
        } catch (SQLException e) {
            throw new IllegalStateException("切换 schema 失败: " + schema, e);
        }
        return SingleConnectionDataSource.of(connection);
    }
}
