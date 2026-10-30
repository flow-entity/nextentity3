package io.github.nextentity.jdbc.exec;

import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.PathProjection;
import io.github.nextentity.core.spec.Projection;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.ValueSelection;
import io.github.nextentity.jdbc.compile.JdbcQuery;
import io.github.nextentity.jdbc.meta.JdbcEntityAttribute;
import io.github.nextentity.jdbc.meta.JdbcEntityType;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// 行装配：实体查询交实体实例（只填标量属性，关联字段保持 `null`），值查询交列宽 = 投影列数的值
/// （单列裸值、多列 `Object[]`）——`Tuple` / DTO 的成形属于 core 的 `RowAssembler`。
final class RowReader {

    private final JdbcMeta meta;

    RowReader(JdbcMeta meta) {
        this.meta = Objects.requireNonNull(meta, "meta");
    }

    @SuppressWarnings("unchecked")
    <R> List<R> read(ResultSet rows, JdbcQuery query) throws SQLException {
        QuerySpec spec = query.spec();
        String sql = query.statement().sql();
        List<R> result = new ArrayList<>();
        if (spec.selection() instanceof EntitySelection) {
            JdbcEntityType<?> entityType = meta.entityType(spec.entityType());
            List<JdbcEntityAttribute> attributes = scalarAttributes(entityType);
            while (rows.next()) {
                result.add((R) readEntity(rows, entityType, attributes, query.columnTypes(), sql));
            }
            return result;
        }
        // Selection 是 sealed，实体分支已返回，剩下的只能是 ValueSelection。
        ValueSelection values = (ValueSelection) spec.selection();
        while (rows.next()) {
            result.add((R) readValue(rows, values, query.columnTypes(), sql));
        }
        return result;
    }

    private static Object readEntity(
            ResultSet rows,
            JdbcEntityType<?> entityType,
            List<JdbcEntityAttribute> attributes,
            List<Class<?>> columnTypes,
            String sql)
            throws SQLException {
        Object entity = entityType.newInstance();
        for (int i = 0; i < attributes.size(); i++) {
            JdbcEntityAttribute attribute = attributes.get(i);
            Object value = ColumnReader.readCell(rows, i + 1, columnTypes.get(i), attribute, sql);
            if (value != null || !attribute.type().isPrimitive()) {
                attribute.set(entity, value);
            }
        }
        return entity;
    }

    private Object readValue(ResultSet rows, ValueSelection selection, List<Class<?>> columnTypes, String sql)
            throws SQLException {
        List<Projection> items = selection.items();
        Object[] cells = new Object[items.size()];
        for (int i = 0; i < items.size(); i++) {
            cells[i] = ColumnReader.readCell(rows, i + 1, columnTypes.get(i), enumAttribute(items.get(i)), sql);
        }
        return cells.length == 1 ? cells[0] : cells;
    }

    /// 值投影若是枚举属性（单列或 join 末段的路径），读出后要转成枚举常量。
    private JdbcEntityAttribute enumAttribute(Projection projection) {
        if (projection instanceof PathProjection path) {
            return resolve(path.path());
        }
        return null;
    }

    private JdbcEntityAttribute resolve(AttributePath path) {
        JdbcEntityType<?> current = meta.entityType(path.entityType());
        JdbcEntityAttribute attribute = null;
        for (String name : path.names()) {
            attribute = current.attribute(name);
            if (attribute == null) {
                return null;
            }
            if (attribute.isAssociation() && attribute.joinColumn() != null) {
                current = meta.entityType(attribute.associationTarget());
            }
        }
        return attribute;
    }

    private static List<JdbcEntityAttribute> scalarAttributes(JdbcEntityType<?> entityType) {
        return entityType.columns().stream()
                .filter(attribute -> !attribute.isAssociation())
                .toList();
    }
}
