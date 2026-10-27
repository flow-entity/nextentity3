package io.github.nextentity.tests.jdbc.support;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Logger;
import javax.sql.DataSource;

/// 单连接 `DataSource`：一个 support 实例内所有查询/写共用同一条物理连接，否则
/// `FOR UPDATE`/乐观锁用例在「开事务的语句」与「改数据的语句」之间会落到两条连接上。
///
/// 交给生产代码的是**代理连接**：`close()` 空操作，其余方法直通。生产侧
/// `JdbcEntityOperationsFactory` 按「取连接 + try-with-resources」写（决策 3：每次操作独立取连接），
/// 不代理的话第一次操作就会把这条物理连接关掉，后续用例全红。
public final class SingleConnectionDataSource implements DataSource {

    private final Connection connection;
    private final Connection proxy;

    private SingleConnectionDataSource(Connection connection) {
        this.connection = connection;
        this.proxy = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, this::invoke);
    }

    /// H2 内存库：库名由调用方给，每个接线类一个独立库名（互不干扰）。
    public static SingleConnectionDataSource h2(String databaseName) {
        String url = "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try {
            return new SingleConnectionDataSource(DriverManager.getConnection(url, "sa", ""));
        } catch (SQLException e) {
            throw new IllegalStateException("H2 连接失败: " + url, e);
        }
    }

    /// 包装一条已建好的连接（MySQL 容器通道用）。
    public static SingleConnectionDataSource of(Connection connection) {
        return new SingleConnectionDataSource(connection);
    }

    /// 物理连接：建表、事务控制用它（`setAutoCommit`/`commit`/`rollback`）。
    public Connection connection() {
        return connection;
    }

    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException("关闭连接失败", e);
        }
    }

    private Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        switch (name) {
            case "close" -> {
                return null;
            }
            case "equals" -> {
                return proxy == args[0];
            }
            case "hashCode" -> {
                return System.identityHashCode(proxy);
            }
            case "toString" -> {
                return "SingleConnectionDataSource[" + connection + "]";
            }
            default -> {
                try {
                    return method.invoke(connection, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        }
    }

    @Override
    public Connection getConnection() {
        return proxy;
    }

    @Override
    public Connection getConnection(String username, String password) {
        return proxy;
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        // 单连接数据源没有日志流配置
    }

    @Override
    public void setLoginTimeout(int seconds) {
        // 连接已建立，登录超时无意义
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger("io.github.nextentity.tests.jdbc");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("不是 " + iface.getName() + " 的包装: " + this);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
