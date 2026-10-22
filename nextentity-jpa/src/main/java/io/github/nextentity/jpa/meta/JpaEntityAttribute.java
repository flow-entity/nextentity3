package io.github.nextentity.jpa.meta;

import io.github.nextentity.core.meta.EntityAttribute;
import java.lang.reflect.Field;
import java.util.Objects;

/// `EntityAttribute` 的 JPA 实现：用反射字段读写，向上找父类声明。
///
/// 只做「按名读写」：不转换值类型——`@Convert` / 枚举 / 时间类型的换算由 Hibernate 负责，
/// 框架在这里再转一次就是双份语义。
final class JpaEntityAttribute implements EntityAttribute {

    private final String name;
    private final Field field;

    JpaEntityAttribute(Class<?> entityType, String name) {
        this.name = name;
        this.field = resolveField(entityType, name);
    }

    private static Field resolveField(Class<?> entityType, String name) {
        for (Class<?> type = entityType; type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // 继续向上找父类声明的字段（实体继承映射很常见）
            }
        }
        throw new IllegalArgumentException("实体 " + entityType.getName() + " 上找不到字段 " + name);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Class<?> type() {
        return field.getType();
    }

    @Override
    public Object get(Object entity) {
        try {
            return field.get(entity);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("读取字段失败: " + field, e);
        }
    }

    @Override
    public void set(Object entity, Object value) {
        Objects.requireNonNull(entity, "entity");
        try {
            field.set(entity, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("写入字段失败: " + field, e);
        }
    }
}
