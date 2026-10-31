package io.github.nextentity.jdbc.exec;

import io.github.nextentity.jdbc.compile.SqlStatement;
import io.github.nextentity.jdbc.dialect.JdbcDialect;
import io.github.nextentity.jdbc.meta.JdbcEntityAttribute;
import io.github.nextentity.jdbc.meta.JdbcEntityType;
import io.github.nextentity.jdbc.meta.JdbcMeta;
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
    static SqlStatement insert(JdbcMeta meta, JdbcEntityType<?> entityType, Object entity, JdbcDialect dialect) {
        List<String> columns = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        for (JdbcEntityAttribute attribute : entityType.columns()) {
            if (attribute.isAssociation()) {
                if (attribute.isJoinColumnInsertable()) {
                    associationColumn(meta, attribute, entity, columns, parameters, dialect);
                }
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
    static SqlStatement update(JdbcMeta meta, JdbcEntityType<?> entityType, Object entity, JdbcDialect dialect) {
        List<String> assignments = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        appendScalars(meta, entityType, entity, assignments, parameters, dialect);
        JdbcEntityAttribute version = entityType.version();
        if (version == null && assignments.isEmpty()) {
            throw new UnsupportedOperationException("实体 " + entityType.type().getName()
                    + " 没有可写标量列，update 无法生成 SET 子句：可写标量列都带"
                    + " @Column(updatable=false)，也没有 @Version 列可以自增；请至少保留一个可更新列");
        }
        if (version != null) {
            assignments.add(identifier(version.column(), version.isColumnQuoted(), dialect) + "=?");
            parameters.add(JdbcTypes.nextVersion(version.get(entity), version.type()));
        }
        StringBuilder sql = new StringBuilder("update ")
                .append(identifier(entityType.table(), entityType.isTableQuoted(), dialect))
                .append(" set ")
                .append(String.join(", ", assignments));
        JdbcEntityAttribute id = entityType.id();
        sql.append(" where ")
                .append(identifier(id.column(), id.isColumnQuoted(), dialect))
                .append("=?");
        parameters.add(JdbcTypes.toJdbc(id.get(entity), id));
        if (version != null) {
            sql.append(" and ")
                    .append(identifier(version.column(), version.isColumnQuoted(), dialect))
                    .append("=?");
            // WHERE 是谓词：version 为 null 必须保持 NULL（0 行 → 乐观锁异常），不能沿用写侧 null→0 的归一。
            parameters.add(JdbcTypes.toLiteral(version.get(entity), version));
        }
        return new SqlStatement(sql.toString(), parameters, List.of());
    }

    /// 追加 `SET` 的赋值（跳过主键、版本与 `updatable=false`）。
    private static void appendScalars(
            JdbcMeta meta,
            JdbcEntityType<?> entityType,
            Object entity,
            List<String> assignments,
            List<Object> parameters,
            JdbcDialect dialect) {
        for (JdbcEntityAttribute attribute : entityType.columns()) {
            List<String> columns = new ArrayList<>();
            List<Object> values = new ArrayList<>();
            if (attribute.isAssociation()) {
                if (attribute.isJoinColumnUpdatable()) {
                    associationColumn(meta, attribute, entity, columns, values, dialect);
                }
            } else if (!attribute.isId() && !attribute.isVersion() && attribute.isColumnUpdatable()) {
                columns.add(identifier(attribute.column(), attribute.isColumnQuoted(), dialect));
                values.add(JdbcTypes.toJdbc(attribute.get(entity), attribute));
            }
            if (!columns.isEmpty()) {
                assignments.add(columns.getFirst() + "=?");
                parameters.add(values.getFirst());
            }
        }
    }

    /// FK 列的写入形态：关联对象 `null` → 跳过列；否则列名 + 关联对象 `@Id` 值
    /// （按目标 `@Id` 的列映射归一，与条件写 `关联.id` 逐字一致）。
    /// `@JoinColumn(insertable/updatable=false)` 的判定在调用方（`insert` / `appendScalars`）做。
    ///
    /// 关联对象非 `null` 但 `@Id` 为 `null`（未持久化的关联）显式拒绝：静默跳过会丢掉 `insert`
    /// 的关联，绑 `NULL` 则会把库里已有的外键清掉。JPA 侧同一输入抛 `TransientObjectException`。
    private static void associationColumn(
            JdbcMeta meta,
            JdbcEntityAttribute attribute,
            Object entity,
            List<String> columns,
            List<Object> parameters,
            JdbcDialect dialect) {
        Object associated = attribute.get(entity);
        if (associated == null) {
            return;
        }
        Object associatedId = attribute.associatedId(associated);
        if (associatedId == null) {
            throw new IllegalArgumentException(
                    "关联对象的主键不能为 null: " + entity.getClass().getName() + "." + attribute.name() + " → "
                            + attribute.associationTarget().getName() + ".id");
        }
        JdbcEntityAttribute foreignKey = JdbcEntityAttribute.foreignKey(
                meta.entityType(attribute.associationTarget()).id(),
                attribute.joinColumn(),
                attribute.isJoinColumnQuoted(),
                attribute.isJoinColumnUpdatable());
        columns.add(identifier(attribute.joinColumn(), attribute.isJoinColumnQuoted(), dialect));
        parameters.add(JdbcTypes.toJdbc(associatedId, foreignKey));
    }

    /// 标识符：quoted 时过方言引用，否则裸名。
    private static String identifier(String name, boolean quoted, JdbcDialect dialect) {
        return quoted ? dialect.quote(name) : name;
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }
}
