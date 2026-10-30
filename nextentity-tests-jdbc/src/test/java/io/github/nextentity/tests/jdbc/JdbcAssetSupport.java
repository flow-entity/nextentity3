package io.github.nextentity.tests.jdbc;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jdbc.JdbcEntityOperationsFactory;
import io.github.nextentity.testing.AssetFixture;
import io.github.nextentity.tests.jdbc.support.JdbcSchema;
import io.github.nextentity.tests.jdbc.support.SingleConnectionDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/// 关联嵌套用例的 JDBC 接线：两张表按夹具落库，并给出两段嵌套路径。
///
/// 关联数据用普通 JDBC 直插（`asset.owner_id`）而不是走写入执行器：夹具是"给定输入"，
/// 不该顺带依赖写路径是否正确。**写路径本身**的证据在
/// {@link io.github.nextentity.testing.AssociationWriteConsistencyTest} 的 JDBC 接线里。
public class JdbcAssetSupport implements AutoCloseable {

    private static final String CLEAR_ASSETS = "delete from asset";
    private static final String CLEAR_OWNERS = "delete from owner";
    private static final String INSERT_OWNER = "insert into owner (id, name) values (?, ?)";
    private static final String INSERT_ASSET = "insert into asset (id, name, owner_id) values (?, ?, ?)";

    private final SingleConnectionDataSource dataSource;

    public JdbcAssetSupport(String databaseName) {
        this(SingleConnectionDataSource.h2(databaseName), JdbcSchema.H2);
    }

    protected JdbcAssetSupport(SingleConnectionDataSource dataSource) {
        this(dataSource, JdbcSchema.MYSQL);
    }

    /// 方言通道的接线：连接由容器给，建表脚本按方言选。
    ///
    /// @param dataSource     已连到本类命名空间的单连接数据源
    /// @param schemaResource 该方言的建表脚本（`JdbcSchema` 里的常量）
    protected JdbcAssetSupport(SingleConnectionDataSource dataSource, String schemaResource) {
        this.dataSource = dataSource;
        JdbcSchema.apply(dataSource.connection(), schemaResource);
    }

    public EntityQuery<AssetEntity> query() {
        return operations().query();
    }

    /// 业务操作入口：关联写入拒绝用例需要走真实写入路径。
    public EntityOperations<AssetEntity> operations() {
        return new JdbcEntityOperationsFactory(dataSource).operations(AssetEntity.class);
    }

    /// 两段嵌套路径：`AssetEntity::owner` → `OwnerEntity::name`。
    public StringPath<AssetEntity> ownerNamePath() {
        return EntityPath.of(AssetEntity::owner).get(OwnerEntity::name);
    }

    public void seedData(List<AssetFixture> fixtures) {
        execute(CLEAR_ASSETS);
        execute(CLEAR_OWNERS);
        Map<String, Long> owners = new LinkedHashMap<>();
        long ownerId = 1L;
        for (AssetFixture fixture : fixtures) {
            String ownerName = fixture.ownerName();
            if (ownerName != null && !owners.containsKey(ownerName)) {
                owners.put(ownerName, ownerId++);
            }
        }
        try (PreparedStatement insertOwner = dataSource.connection().prepareStatement(INSERT_OWNER);
                PreparedStatement insertAsset = dataSource.connection().prepareStatement(INSERT_ASSET)) {
            for (Map.Entry<String, Long> owner : owners.entrySet()) {
                insertOwner.setLong(1, owner.getValue());
                insertOwner.setString(2, owner.getKey());
                insertOwner.addBatch();
            }
            insertOwner.executeBatch();
            for (AssetFixture fixture : fixtures) {
                insertAsset.setLong(1, fixture.id());
                insertAsset.setString(2, fixture.name());
                Long owner = owners.get(fixture.ownerName());
                if (owner == null) {
                    insertAsset.setNull(3, Types.BIGINT);
                } else {
                    insertAsset.setLong(3, owner);
                }
                insertAsset.addBatch();
            }
            insertAsset.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("关联夹具写入失败", e);
        }
    }

    public SingleConnectionDataSource dataSource() {
        return dataSource;
    }

    public <R> R inTransaction(Supplier<R> action) {
        Connection connection = dataSource.connection();
        try {
            connection.setAutoCommit(false);
            R result = action.get();
            connection.commit();
            return result;
        } catch (SQLException e) {
            rollback(connection);
            throw new IllegalStateException("事务失败", e);
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

    /// 直插夹具/清表用：绕开写入执行器（关联列走普通 JDBC 直插）。
    public void execute(String sql) {
        try (Statement statement = dataSource.connection().createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("SQL 执行失败: " + sql, e);
        }
    }

    /// 原始列读回：`select owner_id from asset where id = ?`。
    ///
    /// 走原生 SQL 而不是实体查询：JDBC 读回**不填充关联对象**（USAGE §13.4.4），
    /// 写关联的证据需要的是库里的外键原始值。
    ///
    /// @param assetId 主键
    /// @return 外键值；列为 `NULL` 时返回 `null`
    public Long foreignKeyOf(long assetId) {
        try (PreparedStatement statement =
                dataSource.connection().prepareStatement("select owner_id from asset where id = ?")) {
            statement.setLong(1, assetId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("asset 行不存在: " + assetId);
                }
                long value = rows.getLong(1);
                return rows.wasNull() ? null : value;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取外键失败: " + assetId, e);
        }
    }

    /// 原始列读回：`select name from asset where id = ?`。
    ///
    /// @param assetId 主键
    /// @return 名称
    public String nameOf(long assetId) {
        try (PreparedStatement statement =
                dataSource.connection().prepareStatement("select name from asset where id = ?")) {
            statement.setLong(1, assetId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("asset 行不存在: " + assetId);
                }
                return rows.getString(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取名称失败: " + assetId, e);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
