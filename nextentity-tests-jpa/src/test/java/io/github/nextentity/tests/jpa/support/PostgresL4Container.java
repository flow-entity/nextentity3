package io.github.nextentity.tests.jpa.support;

import java.time.Duration;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/// L4 的 PostgreSQL 容器：一个 JVM 起一个实例，所有容器用例类共享同一个库。
///
/// 与 [MySqlL4Container] 同构：容器在**首次取连接信息时**（本类初始化）启动；Docker 不可用的
/// 机器上用例类先被 [PostgresL4Gate] 判为跳过，这条路径不会被走到——除非调用方加了
/// `-Dnextentity.requireContainer=true`，那时门禁故意放行，让这里启动失败把构建打红。
///
/// 库内隔离靠 `hbm2ddl create-drop`：每个用例类的装置起自己的 `SessionFactory`，启动时
/// 重建自己映射的表。failsafe 默认串行（`forkCount=1`、`reuseForks=true`），所以后一个类
/// 重建表不会打断前一个类的上下文。
///
/// 镜像源默认与 `nextentity-tests-jdbc` 的 PG 通道一致（`docker.xuanyuan.run`），用
/// `-Dnextentity.postgres.image=<repo>:<tag>` 覆盖；换源后必须**重新判断**
/// [DockerImageName#asCompatibleSubstituteFor] 那一行是否还成立。
public final class PostgresL4Container {

    /// 镜像源与版本：与 JDBC 的 PG 通道同一份默认值。
    private static final String DEFAULT_IMAGE = "docker.xuanyuan.run/library/postgres:16";

    /// `asCompatibleSubstituteFor("postgres")` 不能省：镜像站前缀的仓库名不是官方
    /// `library/postgres`，不声明兼容替代就会被 Testcontainers 判成「镜像与 postgres 不兼容」。
    private static final DockerImageName IMAGE = DockerImageName.parse(
                    System.getProperty("nextentity.postgres.image", DEFAULT_IMAGE))
            .asCompatibleSubstituteFor("postgres");

    private static final PostgreSQLContainer<?> CONTAINER = new PostgreSQLContainer<>(IMAGE)
            .withDatabaseName("nextentity_l4")
            .withUsername("test")
            .withPassword("test")
            // PG 首启要跑 initdb（比 MySQL 冷启动更慢）；给足 3 分钟，避免慢磁盘上假红。
            .withStartupTimeout(Duration.ofMinutes(3));

    static {
        CONTAINER.start();
    }

    private PostgresL4Container() {}

    /// 容器暴露的 JDBC 连接串。
    ///
    /// @return `jdbc:postgresql://<host>:<port>/nextentity_l4`
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
