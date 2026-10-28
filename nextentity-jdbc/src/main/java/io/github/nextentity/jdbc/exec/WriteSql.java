package io.github.nextentity.jdbc.exec;

import io.github.nextentity.jdbc.compile.SqlStatement;
import io.github.nextentity.jdbc.dialect.JdbcDialect;
import io.github.nextentity.jdbc.meta.EnumStorage;
import io.github.nextentity.jdbc.meta.JdbcEntityAttribute;
import io.github.nextentity.jdbc.meta.JdbcEntityType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/// `INSERT` / `UPDATE` 语句构造（包内可见，见设计 §7.5：同包测试直接断言 SQL 与绑定值）。
///
/// 只产出 `{sql, parameters}`；执行、生成键回填、版本自增回写都在 {@link JdbcPersistExecutor}。
/// 参数值在这里就转成 JDBC 可直接绑定的形态（枚举按 [EnumStorage] 转 `name()` / `ordinal()`），
/// 执行器只做 `setObject`。
///
/// 标识符：meta 标记为 quoted 的表名 / 列名过 `JdbcDialect#quote`，未标记保持裸名（现有实体零变化）。
final class WriteSql {

    private WriteSql() {}

    /// `insert into t (a, b) values (?, ?)`：跳过自增主键列；关联属性为非 `null` 时拒绝，为 `null` 时该列不出现。
    static SqlStatement insert(JdbcEntityType<?> entityType, Object entity, JdbcDialect dialect) {
        List<String> columns = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        for (JdbcEntityAttribute attribute : entityType.attributes()) {
            if (attribute.isAssociation()) {
                rejectAssociation(entityType, attribute, entity);
                continue;
            }
            if (attribute.isGenerated()) {
                continue;
            }
            columns.add(identifier(attribute.column(), attribute.isColumnQuoted(), dialect));
            parameters.add(parameter(attribute, attribute.get(entity)));
        }
        String sql = "insert into " + identifier(entityType.table(), entityType.isTableQuoted(), dialect) + " ("
                + String.join(", ", columns) + ") values (" + placeholders(columns.size()) + ")";
        return new SqlStatement(sql, immutable(parameters), List.of());
    }

    /// `update t set a=?, b=?, version=? where id=? [and version=?]`：`SET` 列 = 标量属性去掉主键与版本，
    /// 版本列固定排在最后并写「旧值 + 1」；版本列同时出现在 `WHERE` 里。
    /// 没有任何可写标量列且无版本列时显式拒绝（否则会渲染出 `set  where` 这种非法 SQL）。
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
                        "实体 " + entityType.type().getName() + " 没有可写标量列，update 无法生成 SET 子句");
            }
        } else {
            if (!empty) {
                sql.append(", ");
            }
            sql.append(identifier(version.column(), version.isColumnQuoted(), dialect))
                    .append("=?");
            parameters.add(nextVersion(version.get(entity)));
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
        return new SqlStatement(sql.toString(), immutable(parameters), List.of());
    }

    /// 追加 `SET` 的标量列（跳过主键、版本与关联），返回「一个都没追加」。
    private static boolean appendScalars(
            JdbcEntityType<?> entityType,
            Object entity,
            StringBuilder sql,
            List<Object> parameters,
            JdbcDialect dialect) {
        boolean empty = true;
        for (JdbcEntityAttribute attribute : entityType.attributes()) {
            if (attribute.isAssociation()) {
                rejectAssociation(entityType, attribute, entity);
                continue;
            }
            if (attribute.isId() || attribute.isVersion()) {
                continue;
            }
            if (!empty) {
                sql.append(", ");
            }
            sql.append(identifier(attribute.column(), attribute.isColumnQuoted(), dialect))
                    .append("=?");
            parameters.add(parameter(attribute, attribute.get(entity)));
            empty = false;
        }
        return empty;
    }

    /// 标识符：quoted 时过方言引用，否则裸名。
    private static String identifier(String name, boolean quoted, JdbcDialect dialect) {
        return quoted ? dialect.quote(name) : name;
    }

    /// 版本列的下一个值：`null` 视作 0；只支持 `Integer` / `Long`（与 `JpaPersistExecutor` 同文案）。
    static Object nextVersion(Object version) {
        if (version == null) {
            return 0;
        }
        if (version instanceof Integer integer) {
            return integer + 1;
        }
        if (version instanceof Long longValue) {
            return longValue + 1;
        }
        throw new UnsupportedOperationException(
                "@Version 只支持 Integer / Long: " + version.getClass().getName());
    }

    private static void rejectAssociation(JdbcEntityType<?> entityType, JdbcEntityAttribute attribute, Object entity) {
        if (attribute.get(entity) != null) {
            throw new UnsupportedOperationException(
                    "关联属性写入未支持: " + entityType.type().getName() + "." + attribute.name());
        }
    }

    private static Object parameter(JdbcEntityAttribute attribute, Object value) {
        if (value == null) {
            return attribute.isVersion() ? zero(attribute.type()) : null;
        }
        if (attribute.enumStorage() == EnumStorage.NAME) {
            return ((Enum<?>) value).name();
        }
        if (attribute.enumStorage() == EnumStorage.ORDINAL) {
            return ((Enum<?>) value).ordinal();
        }
        return value;
    }

    private static List<Object> immutable(List<Object> parameters) {
        // 参数里允许 null（未赋值列绑 null），所以不能用 List.copyOf。
        return Collections.unmodifiableList(new ArrayList<>(parameters));
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private static Object zero(Class<?> type) {
        if (type == long.class || type == Long.class) {
            return 0L;
        }
        return 0;
    }
}
