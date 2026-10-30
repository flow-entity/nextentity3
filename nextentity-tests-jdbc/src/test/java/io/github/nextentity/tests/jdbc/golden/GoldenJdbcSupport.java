package io.github.nextentity.tests.jdbc.golden;

import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jdbc.JdbcEntityOperationsFactory;
import io.github.nextentity.testing.golden.GoldenFixtures;
import io.github.nextentity.testing.golden.GoldenSchema;
import io.github.nextentity.tests.jdbc.support.JdbcSchema;
import io.github.nextentity.tests.jdbc.support.MySqlL4Container;
import io.github.nextentity.tests.jdbc.support.PostgresL4Container;
import io.github.nextentity.tests.jdbc.support.SingleConnectionDataSource;
import io.github.nextentity.tests.jdbc.support.SqlServerL4Container;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/// golden schema 的 JDBC 接线：H2 每条用例一个独立库，容器通道沿用 `JdbcSchema` 的方言脚本。
///
/// golden 三张表（`golden_customer` / `golden_order` / `golden_order_line`）的 DDL 就在
/// `schema-*.sql` 的 golden 段里，四个方言各一份——**真执行**，不是只为 JPA 注解服务的死资源。
/// 建表脚本按方言执行后，实体元数据来自 `nextentity-testing` 里两个后端共用的同一套注解，
/// 因此「DDL 写的列」与「实体声明的列」对不上时，落地用例的往返断言会立刻红。
public class GoldenJdbcSupport implements AutoCloseable {

    protected final SingleConnectionDataSource dataSource;

    /// H2：独立库名 + H2 建表脚本。
    ///
    /// @param databaseName H2 内存库名
    public GoldenJdbcSupport(String databaseName) {
        this(SingleConnectionDataSource.h2(databaseName), JdbcSchema.H2);
    }

    protected GoldenJdbcSupport(SingleConnectionDataSource dataSource, String schemaResource) {
        this.dataSource = dataSource;
        JdbcSchema.apply(dataSource.connection(), schemaResource);
    }

    /// 某个 golden 实体的写/查入口。
    ///
    /// @param type golden 实体类型
    /// @param <T>  实体类型
    /// @return 生产装配下的操作入口
    public <T> EntityOperations<T> operations(Class<T> type) {
        return new JdbcEntityOperationsFactory(dataSource).operations(type);
    }

    /// 全表查询。
    ///
    /// @param type golden 实体类型
    /// @param <T>  实体类型
    /// @return 该表所有行
    public <T> List<T> query(Class<T> type) {
        return operations(type).query().list();
    }

    /// 直查单值（校验映射写了哪一列、或读回了哪一列）。
    ///
    /// @param sql 单列单行查询
    /// @return 第一行第一列；无行时 `null`
    public String queryString(String sql) {
        try (Statement statement = dataSource.connection().createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException("SQL 执行失败: " + sql, e);
        }
    }

    /// 执行一条 DDL/DML（清表、造边界行用）。
    ///
    /// @param sql 待执行语句
    public void execute(String sql) {
        try (Statement statement = dataSource.connection().createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("SQL 执行失败: " + sql, e);
        }
    }

    /// 清空三表（顺序按外键依赖：子 → 父）。
    public void clear() {
        for (String table : List.of("golden_order_line", "golden_order", "golden_customer")) {
            execute("delete from " + table);
        }
    }

    /// 用夹具写满三张表：父 → 子 → 孙，逐表 `insertAll`。
    public void seedGolden() {
        GoldenFixtures.GoldenData data = GoldenFixtures.data();
        operations(GoldenSchema.Customer.class).insertAll(data.customers());
        operations(GoldenSchema.Order.class).insertAll(data.orders());
        operations(GoldenSchema.OrderLine.class).insertAll(data.lines());
    }

    /// 底层数据源（方言用例需要直连断言时用）。
    ///
    /// @return 单连接数据源
    public SingleConnectionDataSource dataSource() {
        return dataSource;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}

/// MySQL 容器通道的接线（建表脚本换成 `schema-mysql.sql`）。
final class MySqlGoldenSupport extends GoldenJdbcSupport {

    MySqlGoldenSupport() {
        super(SingleConnectionDataSource.of(MySqlL4Container.newConnection()), JdbcSchema.MYSQL);
    }
}

/// PostgreSQL 容器通道的接线（建表脚本换成 `schema-postgres.sql`）。
final class PostgresGoldenSupport extends GoldenJdbcSupport {

    PostgresGoldenSupport() {
        super(PostgresL4Container.dataSource("golden_schema_landing"), JdbcSchema.POSTGRES);
    }
}

/// SQL Server 容器通道的接线（建表脚本换成 `schema-sqlserver.sql`）。
final class SqlServerGoldenSupport extends GoldenJdbcSupport {

    SqlServerGoldenSupport() {
        super(SqlServerL4Container.dataSource("golden_schema_landing"), JdbcSchema.SQLSERVER);
    }
}
