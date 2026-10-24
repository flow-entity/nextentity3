package io.github.nextentity.tests.jpa.support;

import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/// L4 的容器：一个 JVM 起一个 MySQL，所有容器用例类共享同一个实例。
///
/// 单例的理由是预算：容器冷启动几十秒，按类起一次会把这套用例从「跑得起」拖成「没人跑」。
/// 容器在**首次取连接信息时**（本类初始化）启动；Docker 不可用的机器上，用例类先被
/// `@Testcontainers(disabledWithoutDocker = true)` 判为跳过，这条路径不会被走到。
/// 关机交给 Testcontainers 的 Ryuk，随 JVM 退出回收。
///
/// 镜像与 `nextentity-tests-legacy` 的真 MySQL 跑法对齐：那边已验证 8.0.x。
public final class MySqlL4Container {

    private static final DockerImageName IMAGE = DockerImageName.parse("mysql:8.0.18");

    private static final MySQLContainer<?> CONTAINER = new MySQLContainer<>(IMAGE)
            .withDatabaseName("nextentity_l4")
            .withUsername("test")
            .withPassword("test");

    static {
        CONTAINER.start();
    }

    private MySqlL4Container() {}

    /// 容器暴露的 JDBC 连接串。
    ///
    /// @return `jdbc:mysql://<host>:<port>/nextentity_l4` 及其连接参数
    public static String jdbcUrl() {
        return CONTAINER.getJdbcUrl();
    }

    /// 容器建库时用的账号。
    ///
    /// @return 数据库账号
    public static String username() {
        return CONTAINER.getUsername();
    }

    /// 容器建库时用的口令。
    ///
    /// @return 数据库口令
    public static String password() {
        return CONTAINER.getPassword();
    }
}
