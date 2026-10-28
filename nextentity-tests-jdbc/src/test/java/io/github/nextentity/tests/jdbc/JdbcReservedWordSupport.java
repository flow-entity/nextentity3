package io.github.nextentity.tests.jdbc;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jdbc.JdbcEntityOperationsFactory;
import io.github.nextentity.testing.ReservedWordSupport;
import io.github.nextentity.tests.jdbc.support.JdbcSchema;
import io.github.nextentity.tests.jdbc.support.SingleConnectionDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/// 保留字实体的 JDBC 接线：建库建表（方言脚本）、清表写夹具、四个属性引用。
///
/// 清表语句必须按方言引用表名：裸写 `delete from order` 在四个方言上都是语法错误。
public class JdbcReservedWordSupport implements ReservedWordSupport<ReservedWordEntity>, AutoCloseable {

    private final SingleConnectionDataSource dataSource;
    private final EntityOperations<ReservedWordEntity> operations;
    private final String clearAll;

    /// H2：每个接线类一个独立库名（类名小写），建表脚本用 H2 方言（保留字双引号）。
    public JdbcReservedWordSupport(String databaseName) {
        this(SingleConnectionDataSource.h2(databaseName), JdbcSchema.H2, "\"order\"");
    }

    /// 方言通道的接线。
    ///
    /// @param dataSource     已连到本类命名空间的单连接数据源
    /// @param schemaResource 该方言的建表脚本（`JdbcSchema` 里的常量）
    /// @param quotedOrder    该方言引用后的表名（如 `` `order` `` / `"order"` / `[order]`）
    protected JdbcReservedWordSupport(
            SingleConnectionDataSource dataSource, String schemaResource, String quotedOrder) {
        this.dataSource = dataSource;
        this.clearAll = "delete from " + quotedOrder;
        JdbcSchema.apply(dataSource.connection(), schemaResource);
        this.operations = new JdbcEntityOperationsFactory(dataSource).operations(ReservedWordEntity.class);
    }

    @Override
    public EntityQuery<ReservedWordEntity> query() {
        return operations.query();
    }

    @Override
    public void seedData(List<ReservedWordEntity> rows) {
        execute(clearAll);
        operations.insertAll(rows);
    }

    @Override
    public ReservedWordEntity newReservedWord(long id, String name, String desc, Integer key) {
        return new ReservedWordEntity(id, name, desc, key);
    }

    @Override
    public void insert(ReservedWordEntity row) {
        operations.insert(row);
    }

    @Override
    public void insertAll(Iterable<ReservedWordEntity> rows) {
        operations.insertAll(rows);
    }

    @Override
    public void update(ReservedWordEntity row) {
        operations.update(row);
    }

    @Override
    public PathRef<ReservedWordEntity, Long> idRef() {
        return ReservedWordEntity::id;
    }

    @Override
    public PathRef.StringRef<ReservedWordEntity> nameRef() {
        return ReservedWordEntity::name;
    }

    @Override
    public PathRef.StringRef<ReservedWordEntity> descRef() {
        return ReservedWordEntity::desc;
    }

    @Override
    public PathRef.NumberRef<ReservedWordEntity, Integer> keyRef() {
        return ReservedWordEntity::key;
    }

    /// 业务操作入口（写契约用例直接用）。
    ///
    /// @return 操作入口
    public EntityOperations<ReservedWordEntity> operations() {
        return operations;
    }

    /// 数据源（事务用例用）。
    ///
    /// @return 单连接数据源
    public SingleConnectionDataSource dataSource() {
        return dataSource;
    }

    private void execute(String sql) {
        Connection connection = dataSource.connection();
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("建表/清表失败: " + sql, e);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
