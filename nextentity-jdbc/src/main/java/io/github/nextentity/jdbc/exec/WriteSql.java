package io.github.nextentity.jdbc.exec;

import io.github.nextentity.jdbc.compile.SqlStatement;
import io.github.nextentity.jdbc.dialect.JdbcDialect;
import io.github.nextentity.jdbc.meta.JdbcEntityAttribute;
import io.github.nextentity.jdbc.meta.JdbcEntityType;
import io.github.nextentity.jdbc.meta.JdbcTypes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/// `INSERT` / `UPDATE` 语句构造（包内可见：同包测试直接断言 SQL 与绑定值）。
///
/// 只产出 `{sql, parameters}`；执行、生成键回填、版本自增回写都在 {@link JdbcPersistExecutor}。
/// 参数值在这里就统一成 JDBC 可直接绑定的形态（枚举按存储形态转 `name()` / `ordinal()`，
/// `java.time` 转 `java.sql`，见 [JdbcTypes#toJdbc(Object, JdbcEntityAttribute)]），执行器只做 `setObject`。
///
/// 列集规则（3.1.0）：`@Column(insertable/updatable=false)` 与
/// `@JoinColumn(insertable/updatable=false)` 只跳过写侧，读回不受影响；
/// 关联属性非 `null` 时从关联对象的 `@Id` 取 FK 值写入，`null` 时跳过该列（`update` 不能置空，已披露）。
///
/// 标识符：meta 标记为 quoted 的表名 / 列名过 `JdbcDialect#quote`，未标记保持裸名（现有实体零变化）。
final class WriteSql {

    private WriteSql() {}

    /// `insert into t (a, b) values (?, ?)`：跳过自增主键列与 `insertable=false` 的列；
    /// 关联属性非 `null` 时写 FK 列，`null` 时该列不出现。
    static SqlStatement insert(JdbcEntityType<?> entityType, Object entity, JdbcDialect dialect) {
        List<String> columns = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        for (JdbcEntityAttribute attribute : entityType.columns()) {
            if (attribute.isAssociation()) {
                associationColumn(attribute, entity, true, columns, parameters, dialect);
            } else if (!attribute.isGenerated() && attribute.isColumnInsertable()) {
                columns.add(identifier(attribute.column(), attribute.isColumnQuoted(), dialect));
                parameters.add(JdbcTypes.toJdbc(attribute.get(entity), attribute));
            }
        }
        if (columns.isEmpty()) {
            throw new UnsupportedOperationException("实体 " + entityType.type().getName()
                    + " 没有可插入列，insert 无法生成 VALUES 子句：该实体的列全是生成键列或"
                    + " @Column(insertable=false) 列，请至少保留一个可插入的标量列（或用 @GeneratedValue"
                    + " 让主键由数据库生成）");
        }
        String sql = "insert into " + identifier(entityType.table(), entityType.isTableQuoted(), dialect) + " ("
                + String.join(", ", columns) + ") values (" + placeholders(columns.size()) + ")";
        return new SqlStatement(sql, parameters, List.of());
    }

    /// `update t set a=?, b=?, version=? where id=? [and version=?]`：`SET` 列 = 可写标量属性去掉主键与版本，
    /// 版本列固定排在最后并写「旧值 + 1」；版本列同时出现在 `WHERE` 里。
    /// 没有任何可写列且无版本列时显式拒绝（否则会渲染出 `set  where` 这种非法 SQL）。
    static SqlStatement update(JdbcEntityType<?> entityType, Object entity, JdbcDialect dialect) {
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("update ")
                .append(identifier(entityType.table(), entityType.isTableQuoted(), dialect))
                .append(" set ");
        JdbcEntityAttribute version = entityType.version();
        boolean empty = appendScalars(entityType, entity, sql, parameters, dialect);
        if (version == null) {
            if (empty) {
                throw new UnsupportedOperationException(
                        "实体 " + entityType.type().getName()
                                + " 没有可写标量列，update 无法生成 SET 子句：可写标量列都带"
                                + " @Column(updatable=false)，也没有 @Version 列可以自增；请至少保留一个可更新列");
            }
        } else {
            if (!empty) {
                sql.append(", ");
            }
            sql.append(identifier(version.column(), version.isColumnQuoted(), dialect))
                    .append("=?");
            parameters.add(JdbcTypes.nextVersion(version.get(entity), version.type()));
        }
        JdbcEntityAttribute id = entityType.id();
        sql.append(" where ")
                .append(identifier(id.column(), id.isColumnQuoted(), dialect))
                .append("=?");
        parameters.add(id.get(entity));
        if (version != null) {
            sql.append(" and ")
                    .append(identifier(version.column(), version.isColumnQuoted(), dialect))
                    .append("=?");
            parameters.add(version.get(entity));
        }
        return new SqlStatement(sql.toString(), parameters, List.of());
    }

    /// 追加 `SET` 的标量列（跳过主键、版本与 `updatable=false`），返回「一个都没追加」。
    private static boolean appendScalars(
            JdbcEntityType<?> entityType,
            Object entity,
            StringBuilder sql,
            List<Object> parameters,
            JdbcDialect dialect) {
        boolean empty = true;
        for (JdbcEntityAttribute attribute : entityType.columns()) {
            if (attribute.isAssociation()) {
                List<String> columns = new ArrayList<>();
                List<Object> values = new ArrayList<>();
                associationColumn(attribute, entity, false, columns, values, dialect);
                if (!columns.isEmpty()) {
                    if (!empty) {
                        sql.append(", ");
                    }
                    sql.append(columns.getFirst()).append("=?");
                    parameters.add(values.getFirst());
                    empty = false;
                }
                continue;
            }
            if (attribute.isId() || attribute.isVersion() || !attribute.isColumnUpdatable()) {
                continue;
            }
            if (!empty) {
                sql.append(", ");
            }
            sql.append(identifier(attribute.column(), attribute.isColumnQuoted(), dialect))
                    .append("=?");
            parameters.add(JdbcTypes.toJdbc(attribute.get(entity), attribute));
            empty = false;
        }
        return empty;
    }

    /// FK 列的写入形态：`insertable/updatable=false` → 跳过；关联对象 `null` → 跳过列；
    /// 否则列名 + 关联对象 `@Id` 值。返回「是否追加了一列」。
    private static boolean associationColumn(
            JdbcEntityAttribute attribute,
            Object entity,
            boolean insert,
            List<String> columns,
            List<Object> parameters,
            JdbcDialect dialect) {
        if (insert ? !attribute.isJoinColumnInsertable() : !attribute.isJoinColumnUpdatable()) {
            return false;
        }
        Object associated = attribute.get(entity);
        if (associated == null) {
            return false;
        }
        columns.add(identifier(attribute.joinColumn(), attribute.isJoinColumnQuoted(), dialect));
        parameters.add(attribute.associatedId(associated));
        return true;
    }

    /// 标识符：quoted 时过方言引用，否则裸名。
    private static String identifier(String name, boolean quoted, JdbcDialect dialect) {
        return quoted ? dialect.quote(name) : name;
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }
}
