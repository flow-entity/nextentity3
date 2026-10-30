package io.github.nextentity.jpa.meta;

import io.github.nextentity.core.meta.EntityAttribute;
import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;
import java.lang.reflect.Field;
import java.util.Objects;

/// `EntityAttribute` 的 JPA 实现：用反射字段读写，向上找父类声明。
///
/// 只做「按名读写」：不转换值类型——`@Convert` / 枚举 / 时间类型的换算由 Hibernate 负责，
/// 框架在这里再转一次就是双份语义。
///
/// 额外解析两个**写侧**标记（都缺省 `true`）：标量列的 `@Column(updatable)` 与关联外键列的
/// `@JoinColumn(updatable)`。条件更新靠它们 fail-fast，口径与 JDBC 后端
/// `JdbcEntityAttribute.isColumnUpdatable` / `isJoinColumnUpdatable` 一致（只在 SET 侧生效，
/// 读回不受影响）。
final class JpaEntityAttribute implements EntityAttribute {

    private final String name;
    private final Field field;
    private final boolean association;
    private final boolean columnUpdatable;
    private final boolean joinColumnUpdatable;

    /// 标量属性（非关联）的简写：`association = false`。
    JpaEntityAttribute(Class<?> entityType, String name) {
        this(entityType, name, false);
    }

    JpaEntityAttribute(Class<?> entityType, String name, boolean association) {
        this.name = name;
        this.field = resolveField(Objects.requireNonNull(entityType, "entityType"), name);
        this.association = association;
        Column column = field.getAnnotation(Column.class);
        this.columnUpdatable = column == null || column.updatable();
        JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
        this.joinColumnUpdatable = joinColumn == null || joinColumn.updatable();
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

    /// 该属性是不是持有本表外键列的单值关联（`@ManyToOne` / owning `@OneToOne`）。
    boolean association() {
        return association;
    }

    /// 标量列是否可写（`@Column(updatable = false)` → 否）。
    boolean columnUpdatable() {
        return columnUpdatable;
    }

    /// 关联外键列是否可写（`@JoinColumn(updatable = false)` → 否）。
    boolean joinColumnUpdatable() {
        return joinColumnUpdatable;
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
