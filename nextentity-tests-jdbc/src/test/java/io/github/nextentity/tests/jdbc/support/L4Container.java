package io.github.nextentity.tests.jdbc.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.testcontainers.containers.JdbcDatabaseContainer;

/// L4 容器基类：镜像覆盖属性、连接建立、启动/关闭。
///
/// 三个方言容器各自负责**库级隔离**（MySQL 单库共享、PG 每类一个 schema、MSSQL 每类一个
/// database）与各自建表脚本；共同部分只留在这里，避免三份复制后漂移。
///
/// @param <C> Testcontainers 容器类型
public abstract class L4Container<C extends JdbcDatabaseContainer<?>> {

    private final C container;

    protected L4Container(C container) {
        this.container = container;
    }

    /// 读取镜像系统属性，缺省或空白时用默认镜像。
    ///
    /// @param property 系统属性名（如 `nextentity.postgres.image`）
    /// @param fallback 默认镜像
    /// @return 实际镜像名
    protected static String imageOf(String property, String fallback) {
        String value = System.getProperty(property);
        return value == null || value.isBlank() ? fallback : value;
    }

    /// 建立一条到容器库的连接（容器已启动后调用）。
    ///
    /// @return JDBC 连接（调用方负责关闭/包装）
    protected Connection openConnection() {
        String url = container.getJdbcUrl();
        try {
            return DriverManager.getConnection(url, container.getUsername(), container.getPassword());
        } catch (SQLException e) {
            throw new IllegalStateException("连接容器失败: " + url, e);
        }
    }

    /// Testcontainers 容器实例（子类拼 URL、取 host/port 用）。
    ///
    /// @return 容器
    protected C container() {
        return container;
    }
}
