package io.github.nextentity.tests.jpa.support;

import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/// L4 的容器：一个 JVM 起一个 MySQL，所有容器用例类共享同一个实例。
///
/// 单例的理由是预算：容器冷启动几十秒，按类起一次会把这套用例从「跑得起」拖成「没人跑」。
/// 容器在**首次取连接信息时**（本类初始化）启动；Docker 不可用的机器上，用例类先被
/// [MySqlL4Gate] 判为跳过，这条路径不会被走到——除非调用方加了
/// `-Dnextentity.requireContainer=true`，那时门禁故意放行，让这里启动失败把构建打红。
/// 关机交给 Testcontainers 的 Ryuk，随 JVM 退出回收。
///
/// 镜像与 `nextentity-tests-legacy` 的真 MySQL 跑法对齐：那边已验证 8.0.x。
///
/// 镜像走 `docker.xuanyuan.run` 镜像源，不走 `registry-1.docker.io`：这台机器上直连 Docker Hub 会
/// `EOF`，而容器用例是 L4 的唯一载体，镜像拉不到就等于这层没跑。要换源或换版本用
/// `-Dnextentity.mysql.image=<repo>:<tag>` 覆盖，不必改代码；换源后必须**重新判断**
/// [DockerImageName#asCompatibleSubstituteFor] 那一行是否还成立（见下）。
public final class MySqlL4Container {

    /// 镜像源与版本：默认走 `docker.xuanyuan.run`（内网/受限网络下的可用源）。
    private static final String DEFAULT_IMAGE = "docker.xuanyuan.run/library/mysql:8.0.18";

    /// `asCompatibleSubstituteFor(\"mysql\")` 不能省：`MySQLContainer` 会校验镜像仓库名，
    /// 镜像站前缀的仓库叫 `docker.xuanyuan.run/library/mysql`，不改声明就会被判成
    /// 「镜像与 mysql 不兼容」而拒启。声明的是**兼容替代**，不是绕过版本检查。
    private static final DockerImageName IMAGE = DockerImageName.parse(
                    System.getProperty("nextentity.mysql.image", DEFAULT_IMAGE))
            .asCompatibleSubstituteFor("mysql");

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
