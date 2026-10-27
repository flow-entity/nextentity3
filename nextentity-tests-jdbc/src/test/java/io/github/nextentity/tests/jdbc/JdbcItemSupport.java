package io.github.nextentity.tests.jdbc;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jdbc.JdbcEntityOperationsFactory;
import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.ItemStatus;
import io.github.nextentity.tests.jdbc.support.JdbcSchema;
import io.github.nextentity.tests.jdbc.support.SingleConnectionDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.function.Supplier;

/// Item 家族（27 个接线类 + 变形题）的 JDBC 接线：建库建表、按夹具清表写入、给出六个属性引用。
///
/// 与 `nextentity-tests-jpa/JpaItemSupport` 同形，区别只在连接：JDBC 后端直接用 `DataSource`，
/// 不经过 `EntityManager`，所以「夹具写入走生产路径」（`operations.insertAll`）、清表走 JDBC 直连。
public class JdbcItemSupport implements ConsistencySupport<ItemEntity>, AutoCloseable {

    private static final String CLEAR_ITEMS = "delete from item";

    private final SingleConnectionDataSource dataSource;
    private final EntityOperations<ItemEntity> operations;

    /// H2：每个接线类一个独立库名（类名小写），建表幂等。
    public JdbcItemSupport(String databaseName) {
        this(SingleConnectionDataSource.h2(databaseName));
    }

    protected JdbcItemSupport(SingleConnectionDataSource dataSource) {
        this.dataSource = dataSource;
        JdbcSchema.apply(dataSource.connection());
        this.operations = new JdbcEntityOperationsFactory(dataSource).operations(ItemEntity.class);
    }

    @Override
    public EntityQuery<ItemEntity> query() {
        return operations.query();
    }

    @Override
    public void seedData(List<ItemEntity> items) {
        execute(CLEAR_ITEMS);
        operations.insertAll(items);
    }

    @Override
    public ItemEntity newItem(long id, String name, Integer score, Integer score2, String note, ItemStatus status) {
        return new ItemEntity(id, name, score, score2, note, status);
    }

    @Override
    public PathRef<ItemEntity, Long> idRef() {
        return ItemEntity::id;
    }

    @Override
    public PathRef.StringRef<ItemEntity> nameRef() {
        return ItemEntity::name;
    }

    @Override
    public PathRef.NumberRef<ItemEntity, Integer> scoreRef() {
        return ItemEntity::score;
    }

    @Override
    public PathRef.NumberRef<ItemEntity, Integer> score2Ref() {
        return ItemEntity::score2;
    }

    @Override
    public PathRef.StringRef<ItemEntity> noteRef() {
        return ItemEntity::note;
    }

    @Override
    public PathRef<ItemEntity, ItemStatus> statusRef() {
        return ItemEntity::status;
    }

    /// 业务操作入口（写契约/生成键/版本用例直接用）。
    public EntityOperations<ItemEntity> operations() {
        return operations;
    }

    public SingleConnectionDataSource dataSource() {
        return dataSource;
    }

    /// 事务边界：`FOR UPDATE` 用例必须在同一事务里「取行 + 释放」，support 只负责开关 autocommit。
    public <R> R inTransaction(Supplier<R> action) {
        Connection connection = dataSource.connection();
        try {
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new IllegalStateException("开启事务失败", e);
        }
        try {
            R result = action.get();
            connection.commit();
            return result;
        } catch (SQLException e) {
            rollback(connection);
            throw new IllegalStateException("提交事务失败", e);
        } catch (RuntimeException e) {
            rollback(connection);
            throw e;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                throw new IllegalStateException("恢复自动提交失败", e);
            }
        }
    }

    private void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            throw new IllegalStateException("回滚失败", e);
        }
    }

    /// 清表/直插用：绕开写入执行器（关联夹具、DDL 数据准备）。
    public void execute(String sql) {
        try (Statement statement = dataSource.connection().createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("SQL 执行失败: " + sql, e);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
