package io.github.nextentity.jdbc;

import io.github.nextentity.core.EntityOperationsFactory;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.core.repo.SimpleEntityOperationsFactory;
import io.github.nextentity.jdbc.compile.JdbcCompiler;
import io.github.nextentity.jdbc.compile.JdbcQuery;
import io.github.nextentity.jdbc.dialect.JdbcDialect;
import io.github.nextentity.jdbc.exec.JdbcExecutor;
import io.github.nextentity.jdbc.exec.JdbcPersistExecutor;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import jakarta.persistence.PersistenceException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/// JDBC 后端的 `EntityOperationsFactory`：探测方言，然后把四个零件交给 core 的装配器。
///
/// 不持有连接、不开事务：每次操作独立 `getConnection()`，事务边界由调用方持有（设计 §9.2 决策 3）。
/// 元数据是应用级的，`JdbcMeta` 只建一次并缓存。
public final class JdbcEntityOperationsFactory implements EntityOperationsFactory {

    private static final String DATA_SOURCE = "dataSource";

    private final JdbcDialect dialect;
    private final SimpleEntityOperationsFactory<JdbcQuery> delegate;

    /// 创建工厂：构造期取一条连接读数据库产品名，探测完立即关闭。
    ///
    /// @param dataSource 数据源
    /// @throws UnsupportedOperationException 产品名不是 MySQL / H2（见设计 §6.2）
    /// @throws PersistenceException          取连接或读产品名失败
    public JdbcEntityOperationsFactory(DataSource dataSource) {
        this(dataSource, detect(dataSource));
    }

    /// 创建工厂：方言由调用方指定（测试或诊断用，不探测）。
    ///
    /// @param dataSource 数据源
    /// @param dialect    方言
    public JdbcEntityOperationsFactory(DataSource dataSource, JdbcDialect dialect) {
        Objects.requireNonNull(dataSource, DATA_SOURCE);
        JdbcDialect selected = Objects.requireNonNull(dialect, "dialect");
        this.dialect = selected;
        JdbcMeta meta = new JdbcMeta();
        JdbcCompiler compiler = new JdbcCompiler(meta, selected);
        this.delegate = new SimpleEntityOperationsFactory<>(
                meta::entityType,
                compiler,
                new JdbcExecutor(dataSource, meta, compiler),
                type -> new JdbcPersistExecutor<>(dataSource, meta.entityType(type), selected));
    }

    @Override
    public <T> EntityOperations<T> operations(Class<T> entityClass) {
        return delegate.operations(entityClass);
    }

    /// 本次使用的方言。
    public JdbcDialect dialect() {
        return dialect;
    }

    private static JdbcDialect detect(DataSource dataSource) {
        Objects.requireNonNull(dataSource, DATA_SOURCE);
        try (Connection connection = dataSource.getConnection()) {
            return JdbcDialect.of(connection.getMetaData().getDatabaseProductName());
        } catch (SQLException e) {
            throw new PersistenceException("获取数据库产品名失败，无法识别 JDBC 方言", e);
        }
    }
}
