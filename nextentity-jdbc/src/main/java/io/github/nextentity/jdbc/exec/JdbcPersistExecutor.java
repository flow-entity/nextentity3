package io.github.nextentity.jdbc.exec;

import io.github.nextentity.core.exec.PersistExecutor;
import io.github.nextentity.jdbc.compile.SqlStatement;
import io.github.nextentity.jdbc.meta.JdbcEntityAttribute;
import io.github.nextentity.jdbc.meta.JdbcEntityType;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/// JDBC 写入器：`INSERT` / `UPDATE` 的语句来自 {@link WriteSql}，这里只负责执行与副作用。
///
/// 副作用有三件：自增主键回填到入参实体、`@Version` 自增回写、行数不等于 1 时抛 JPA 异常族。
/// 不管理事务——`DataSource` 自动提交，事务边界由调用方持有（设计 §9.2 决策 3）。
///
/// 与 JPA 侧的差异只在实现手段：没有 identity map，`update` 总是发语句，不做 `contains` 短路。
public final class JdbcPersistExecutor<T> implements PersistExecutor<T> {

    private static final String EXECUTION_FAILED = "SQL 执行失败: ";
    private static final String ENTITY = "entity";
    private static final String ENTITIES = "entities";
    private static final String MISSING_ID = "主键不能为 null: ";

    private final DataSource dataSource;
    private final JdbcEntityType<T> entityType;

    /// 创建写入器。
    ///
    /// @param dataSource 数据源（每次操作独立取连接）
    /// @param entityType 实体元数据（表名、列名、主键 / 版本句柄）
    public JdbcPersistExecutor(DataSource dataSource, JdbcEntityType<T> entityType) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.entityType = Objects.requireNonNull(entityType, "entityType");
    }

    /// 插入一条：自增主键写后回填；无生成键时不接受 `null` 主键。
    @Override
    public void insert(T entity) {
        Objects.requireNonNull(entity, ENTITY);
        JdbcEntityAttribute id = entityType.id();
        if (id.isGenerated()) {
            insertGenerated(entity, id);
            return;
        }
        if (id.get(entity) == null) {
            throw new IllegalArgumentException(MISSING_ID + entityType.type().getName());
        }
        executeUpdate(WriteSql.insert(entityType, entity));
        writeBackVersion(entity);
    }

    /// 批量插入：自增主键逐条执行（每行都要取号）；否则一条 `PreparedStatement` 走 `addBatch`。
    @Override
    public void insertAll(Iterable<T> entities) {
        Objects.requireNonNull(entities, ENTITIES);
        if (entityType.id().isGenerated()) {
            for (T entity : entities) {
                insert(entity);
            }
            return;
        }
        insertBatch(entities);
    }

    /// 更新一条：`WHERE id [AND version]` 命中 0 行时抛 `EntityNotFoundException` /
    /// `OptimisticLockException`（与 `JpaPersistExecutor` 同类型同文案），成功后回写版本。
    @Override
    public void update(T entity) {
        Objects.requireNonNull(entity, ENTITY);
        updateOne(entity);
    }

    /// 逐条更新。
    @Override
    public void updateAll(Iterable<T> entities) {
        Objects.requireNonNull(entities, ENTITIES);
        for (T entity : entities) {
            Objects.requireNonNull(entity, ENTITY);
            updateOne(entity);
        }
    }

    private void insertGenerated(T entity, JdbcEntityAttribute id) {
        SqlStatement statement = WriteSql.insert(entityType, entity);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement prepared =
                        connection.prepareStatement(statement.sql(), Statement.RETURN_GENERATED_KEYS)) {
            JdbcExecutor.bind(prepared, statement.parameters());
            prepared.executeUpdate();
            try (ResultSet keys = prepared.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new PersistenceException("未返回生成键: " + statement.sql());
                }
                id.set(entity, identifier(GeneratedKeys.read(keys, id.column(), statement.sql()), id.type()));
            }
        } catch (SQLException e) {
            throw new PersistenceException(EXECUTION_FAILED + statement.sql(), e);
        }
        writeBackVersion(entity);
    }

    private void insertBatch(Iterable<T> entities) {
        List<T> batch = new ArrayList<>();
        List<SqlStatement> statements = new ArrayList<>();
        for (T entity : entities) {
            Objects.requireNonNull(entity, ENTITY);
            if (entityType.id().get(entity) == null) {
                throw new IllegalArgumentException(
                        MISSING_ID + entityType.type().getName());
            }
            batch.add(entity);
            statements.add(WriteSql.insert(entityType, entity));
        }
        if (batch.isEmpty()) {
            return;
        }
        String sql = statements.getFirst().sql();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement prepared = connection.prepareStatement(sql)) {
            for (SqlStatement statement : statements) {
                JdbcExecutor.bind(prepared, statement.parameters());
                prepared.addBatch();
            }
            prepared.executeBatch();
        } catch (SQLException e) {
            throw new PersistenceException(EXECUTION_FAILED + sql, e);
        }
        batch.forEach(this::writeBackVersion);
    }

    private void updateOne(T entity) {
        JdbcEntityAttribute id = entityType.id();
        JdbcEntityAttribute version = entityType.version();
        Object idValue = id.get(entity);
        Object versionValue = version == null ? null : version.get(entity);
        int affected = executeUpdate(WriteSql.update(entityType, entity));
        if (affected != 1) {
            String message = "实体 " + entityType.type().getName() + " 不存在: id=" + idValue;
            if (version == null) {
                throw new EntityNotFoundException(message);
            }
            throw new OptimisticLockException(message + ", version=" + versionValue);
        }
        if (version != null) {
            version.set(entity, nextVersion(version, versionValue));
        }
    }

    private int executeUpdate(SqlStatement statement) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement prepared = connection.prepareStatement(statement.sql())) {
            JdbcExecutor.bind(prepared, statement.parameters());
            return prepared.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException(EXECUTION_FAILED + statement.sql(), e);
        }
    }

    private void writeBackVersion(T entity) {
        JdbcEntityAttribute version = entityType.version();
        if (version != null && version.get(entity) == null) {
            version.set(entity, zero(version.type()));
        }
    }

    private static Object identifier(long value, Class<?> type) {
        if (type == Integer.class || type == int.class) {
            return Math.toIntExact(value);
        }
        return value;
    }

    private static Object nextVersion(JdbcEntityAttribute version, Object value) {
        Object next = WriteSql.nextVersion(value);
        if (version.type() == long.class || version.type() == Long.class) {
            return ((Number) next).longValue();
        }
        return next;
    }

    private static Object zero(Class<?> type) {
        if (type == long.class || type == Long.class) {
            return 0L;
        }
        return 0;
    }
}
