package io.github.nextentity.integration.config.env;

import org.testcontainers.containers.JdbcDatabaseContainer;

/// 由 Testcontainers 现起容器的数据库环境。
///
/// 容器**懒启动**：只有真正读连接信息时才 `start()`。这样「已有外部实例」的环境
/// （见 `MysqlEnvironmentVariables` 的 `NEXTENTITY_LEGACY_MYSQL_URL`）不会因为类加载就白起一个容器，
/// 也不会在 Docker 不可用时把整个 JVM 拖死在静态初始化里。
public abstract class DbContainerEnvironmentVariables implements DatabaseEnvironmentVariables {

    private JdbcDatabaseContainer<?> container;

    /// 创建（未启动的）容器；调用方负责缓存与启动。
    protected abstract JdbcDatabaseContainer<?> createContainer();

    protected final synchronized JdbcDatabaseContainer<?> getContainer() {
        if (container == null) {
            container = createContainer();
            container.start();
        }
        return container;
    }

    @Override
    public String getJdbcUrl() {
        return getContainer().getJdbcUrl();
    }

    @Override
    public String getUsername() {
        return getContainer().getUsername();
    }

    @Override
    public String getPassword() {
        return getContainer().getPassword();
    }

    @Override
    public String getDriverClassName() {
        return getContainer().getDriverClassName();
    }
}
