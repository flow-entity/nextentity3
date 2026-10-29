package io.github.nextentity.tests.jdbc.support;

import java.sql.Connection;
import java.sql.SQLException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/// L4 的容器：一个 JVM 起一个 MySQL，所有容器用例类共享同一个实例与同一个库。
///
/// 与 `nextentity-tests-jpa` 的容器**复刻不共享**：那边在它的 `src/test` 里（没有 test-jar），
/// 依赖它会拖进 Hibernate / Spring，违反 AGENTS「jdbc 不得依赖 Hibernate」。
///
/// 单例的理由是预算：容器冷启动几十秒，按类起一次会把 31 个类拖成「没人跑」。Docker 不可用时
/// 由 [L4Gate] 先跳过；只有加了 `-Dnextentity.requireContainer=true` 才会故意放行、让这里
/// 启动失败把构建打红。
public final class MySqlL4Container extends L4Container<MySQLContainer<?>> {

    /// 镜像源与版本：与 tests-jpa 对齐（`docker.xuanyuan.run`，这台机器直连 Docker Hub 会 EOF）。
    private static final String DEFAULT_IMAGE = "docker.xuanyuan.run/library/mysql:8.0.18";

    private static final MySqlL4Container INSTANCE = new MySqlL4Container();

    private MySqlL4Container() {
        // `asCompatibleSubstituteFor("mysql")` 不能省：镜像站前缀的仓库名过不了 `MySQLContainer` 的校验。
        super(new MySQLContainer<>(DockerImageName.parse(imageOf("nextentity.mysql.image", DEFAULT_IMAGE))
                        .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("nextentity_jdbc_l4")
                .withUsername("test")
                .withPassword("test"));
        container().start();
        // 31 个类串跑同一个库：DDL 只做一次，类内重复 apply 也幂等。
        try (Connection connection = openConnection()) {
            JdbcSchema.apply(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("容器库建表失败", e);
        }
    }

    /// 每个接线类一条连接（`SingleConnectionDataSource` 包着它）；类结束由 `@AfterAll` 关闭。
    ///
    /// @return 新连接
    public static Connection newConnection() {
        return INSTANCE.openConnection();
    }
}
