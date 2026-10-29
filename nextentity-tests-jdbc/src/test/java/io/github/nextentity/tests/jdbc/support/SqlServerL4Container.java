package io.github.nextentity.tests.jdbc.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.utility.DockerImageName;

/// L4 的 SQL Server 容器：一个 JVM 起一个实例（`sa` 口令固定），每个接线类一个独立 **database**。
///
/// MSSQL 库里没有「schema 隔离」的办法——查询一律不带 schema 前缀时解析到默认 schema `dbo`，
/// 所以只能按 database 隔离；`create database` 必须走动态 SQL（同批多语句会报错）。
public final class SqlServerL4Container extends L4Container<MSSQLServerContainer<?>> {

    private static final String IMAGE_PROPERTY = "nextentity.sqlserver.image";

    private static final String DEFAULT_IMAGE = "mcr.microsoft.com/mssql/server:2022-latest";

    /// 容器 `sa` 口令：MSSQL 要求大写/小写/数字/符号四类中至少三类，长度 ≥ 8。
    private static final String PASSWORD = "NextEntityTest1!";

    private static final SqlServerL4Container INSTANCE = new SqlServerL4Container();

    private SqlServerL4Container() {
        super(new MSSQLServerContainer<>(DockerImageName.parse(imageOf(IMAGE_PROPERTY, DEFAULT_IMAGE))
                        .asCompatibleSubstituteFor("mcr.microsoft.com/mssql/server"))
                .acceptLicense()
                .withPassword(PASSWORD)
                .withStartupTimeout(Duration.ofMinutes(5)));
        container().start();
    }

    /// 打开一条连到指定 database 的连接并建好表；database 名由接线类给（小写类名）。
    ///
    /// @param database 本类专用的 database 名
    /// @return 单连接数据源（调用方在 `@AfterAll` 关闭）
    public static SingleConnectionDataSource dataSource(String database) {
        try (Connection master = DriverManager.getConnection(url("master"), "sa", PASSWORD);
                Statement statement = master.createStatement()) {
            statement.execute("if db_id(N'" + database + "') is null exec(N'create database [" + database + "]')");
        } catch (SQLException e) {
            throw new IllegalStateException("创建 database 失败: " + database, e);
        }
        Connection connection;
        try {
            connection = DriverManager.getConnection(url(database), "sa", PASSWORD);
        } catch (SQLException e) {
            throw new IllegalStateException("连接 database 失败: " + database, e);
        }
        return SingleConnectionDataSource.of(connection);
    }

    private static String url(String database) {
        return "jdbc:sqlserver://" + INSTANCE.container().getHost() + ":"
                + INSTANCE.container().getMappedPort(1433) + ";databaseName=" + database
                + ";encrypt=false;trustServerCertificate=true";
    }
}
