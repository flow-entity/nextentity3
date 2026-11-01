package io.github.nextentity.jdbc.exec;

import io.github.nextentity.core.spec.AggregateProjection;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.ComputedProjection;
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

    /// 值投影对应的列映射：路径投影（单列、join 末段、嵌入叶子）与 `max` / `min` 聚合都按列反转换
    /// （枚举存储形态 → 常量、`@Convert` 的数据库形态 → 属性值）；其余投影返回 `null`，交回原始值。
    private JdbcEntityAttribute enumAttribute(Projection projection) {
        return switch (projection) {
            case PathProjection path -> resolve(path.path());
            case AggregateProjection aggregate -> aggregateAttribute(aggregate);
            case ComputedProjection ignored -> null;
        };
    }

    /// 聚合投影的列映射：只有 `max` / `min` 是**保形**的——结果就是该列的某一个实际存储值，交给
    /// `ColumnReader#readCell` 与路径投影读回同一列完全同域，反转换因此是双射的。
    ///
    /// `count` / `countDistinct` 的结果是 `Long`、`avg` 是 `Double`，结果域与列值域无关（对枚举列套
    /// `Enum.valueOf` 会炸）；`sum` 的结果域是「转换器保值的和」，只有加性转换器才落在列值域上，
    /// 非保值转换器会把和值转成非法属性值——三者一律不反转换，交回原始值。
    private JdbcEntityAttribute aggregateAttribute(AggregateProjection aggregate) {
        return switch (aggregate.function()) {
            case MAX, MIN -> resolve(aggregate.path());
            case AVG, SUM, COUNT, COUNT_DISTINCT -> null;
        };
    }

    private JdbcEntityAttribute resolve(AttributePath path) {
        JdbcEntityType<?> current = meta.entityType(path.entityType());
        JdbcEntityAttribute attribute = null;
        List<String> names = path.names();
        for (int i = 0; i < names.size(); i++) {
            attribute = current.attribute(names.get(i));
            if (attribute == null) {
                return null;
            }
            if (attribute.isEmbedded()) {
                // 嵌入列已扁平化：叶子只支持 `外层属性.嵌入字段` 两段，与 compile/SqlPaths#embedded 同规则
                // （三段路径在编译期就被拒，这里不会静默截断）。
                return i + 1 < names.size() ? attribute.embeddedColumn(names.get(i + 1)) : null;
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
