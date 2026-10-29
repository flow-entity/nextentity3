package io.github.nextentity.jdbc.meta;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// 注解扫描入口：把实体类解析成表名 + 属性表 + id/version + 无参构造器（`JdbcEntityType` 的数据来源）。
///
/// 规则见设计 §4.1，拒绝清单见 §4.3 #1–#14；细节分散在 [JdbcEntityRules] / [JdbcColumns] / [JdbcAttributes]。
final class JdbcEntityScan {

    /// 扫描结果：后续查询直接读这些字段，不再解析注解。
    record Scanned<T>(
            Class<T> type,
            String table,
            boolean tableQuoted,
            List<JdbcEntityAttribute> attributes,
            Map<String, JdbcEntityAttribute> attributesByName,
            JdbcEntityAttribute id,
            JdbcEntityAttribute version,
            Constructor<T> constructor) {}

    private JdbcEntityScan() {}

    static <T> Scanned<T> scan(Class<T> type) {
        Objects.requireNonNull(type, "type");
        Entity entity = type.getAnnotation(Entity.class);
        if (entity == null) {
            throw new IllegalArgumentException("缺少 @Entity 注解: " + type.getName());
        }
        JdbcEntityRules.rejectClassLevel(type);
        JdbcAttributes attributes = new JdbcAttributes(type);
        for (Field field : JdbcEntityRules.fields(type)) {
            attributes.add(field);
        }
        TableName table = tableName(type, entity);
        return new Scanned<>(
                type,
                table.name(),
                table.quoted(),
                attributes.attributes(),
                attributes.byName(),
                attributes.id(),
                attributes.version(),
                constructor(type));
    }

    /// 表名：`@Table(name)` → `@Entity(name)` → 类简名；反引号包裹的名字剥掉并记 quoted（§4.1 Phase F）。
    private static TableName tableName(Class<?> type, Entity entity) {
        Table table = type.getAnnotation(Table.class);
        if (table != null) {
            TableName declared = declaredTableName(type, table.name());
            if (declared != null) {
                return declared;
            }
        }
        TableName declared = declaredTableName(type, entity.name());
        return declared != null ? declared : new TableName(type.getSimpleName(), false);
    }

    private static TableName declaredTableName(Class<?> type, String raw) {
        String name = JdbcNames.bare(raw, type.getName());
        return name.isEmpty() ? null : new TableName(name, JdbcNames.isQuoted(raw));
    }

    /// 表名 + quoted 标志。
    private record TableName(String name, boolean quoted) {}

    /// 无参构造：protected / private 放行（`setAccessible`），缺失 → §4.3 #13。
    private static <T> Constructor<T> constructor(Class<T> type) {
        try {
            Constructor<T> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor;
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("实体缺少无参构造: " + type.getName(), e);
        }
    }
}
