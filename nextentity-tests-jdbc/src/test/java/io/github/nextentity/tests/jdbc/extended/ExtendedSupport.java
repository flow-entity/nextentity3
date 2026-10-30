package io.github.nextentity.tests.jdbc.extended;

import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jdbc.JdbcEntityOperationsFactory;
import io.github.nextentity.tests.jdbc.support.JdbcSchema;
import io.github.nextentity.tests.jdbc.support.MySqlL4Container;
import io.github.nextentity.tests.jdbc.support.SingleConnectionDataSource;
import io.github.nextentity.tests.jdbc.support.SqlServerL4Container;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/// 3.1.0 能力面落库用例的接线：H2 每条用例一个独立库，容器通道沿用 `JdbcSchema` 的方言脚本。
public class ExtendedSupport implements AutoCloseable {

    protected final SingleConnectionDataSource dataSource;

    /// H2：独立库名 + H2 建表脚本。
    public ExtendedSupport(String databaseName) {
        this(SingleConnectionDataSource.h2(databaseName), JdbcSchema.H2);
    }

    protected ExtendedSupport(SingleConnectionDataSource dataSource) {
        this(dataSource, JdbcSchema.MYSQL);
    }

    protected ExtendedSupport(SingleConnectionDataSource dataSource, String schemaResource) {
        this.dataSource = dataSource;
        JdbcSchema.apply(dataSource.connection(), schemaResource);
    }

    public <T> EntityOperations<T> operations(Class<T> type) {
        return new JdbcEntityOperationsFactory(dataSource).operations(type);
    }

    public <T> List<T> query(Class<T> type) {
        return operations(type).query().list();
    }

    /// 直查单值（校验映射写了哪一列、或读回了哪一列）。
    public String queryString(String sql) {
        try (Statement statement = dataSource.connection().createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException("SQL 执行失败: " + sql, e);
        }
    }

    public void execute(String sql) {
        try (Statement statement = dataSource.connection().createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("SQL 执行失败: " + sql, e);
        }
    }

    public SingleConnectionDataSource dataSource() {
        return dataSource;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}

/// MySQL 容器通道的接线（建表脚本换成 `schema-mysql.sql`）。
final class MySqlExtendedSupport extends ExtendedSupport {

    MySqlExtendedSupport() {
        super(SingleConnectionDataSource.of(MySqlL4Container.newConnection()));
    }
}

/// SQL Server 容器通道的接线（建表脚本换成 `schema-sqlserver.sql`）。
final class SqlServerExtendedSupport extends ExtendedSupport {

    SqlServerExtendedSupport() {
        super(SqlServerL4Container.dataSource("extended_capabilities"), JdbcSchema.SQLSERVER);
    }
}
