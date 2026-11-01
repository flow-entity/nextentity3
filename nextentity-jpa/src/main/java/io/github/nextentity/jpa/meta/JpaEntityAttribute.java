package io.github.nextentity.jpa.meta;

import io.github.nextentity.core.meta.EntityAttribute;
import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;

/// `EntityAttribute` 的 JPA 实现：按**映射声明成员**读写（字段访问读字段，`@Access(PROPERTY)`
/// 读 getter），向上找父类声明。
///
/// 取值必须与 Hibernate 同源：`@Access(PROPERTY)` 的实体，脏检查与 UPDATE 语句都按 access type
/// 读 getter，本层若只读字段，同一个对象在「托管实体 + flush」与「游离实体 + `update(entity)`」
/// 两条路径下会落库两个不同的值（getter 做归一化时），且不抛任何异常。
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
    /// 映射声明所在的 getter；`null` 表示字段访问（读字段）。
    private final Method getter;
    private final boolean association;
    private final boolean columnUpdatable;
    private final boolean joinColumnUpdatable;

    /// 标量属性（非关联）的简写：`association = false`。
    JpaEntityAttribute(Class<?> entityType, String name) {
        this(entityType, name, false);
    }

    JpaEntityAttribute(Class<?> entityType, String name, boolean association) {
        this(entityType, name, association, null);
    }

    /// `declared` 是映射声明所在的成员（字段或 getter）：`@Access(PROPERTY)` 的实体把
    /// `@Column` / `@JoinColumn` 写在 getter 上，字段上没有注解，按 access type 取注解才看得到
    /// 只读标记；为 `null` 时回落到字段（字段访问实体）。
    JpaEntityAttribute(Class<?> entityType, String name, boolean association, AnnotatedElement declared) {
        this.name = name;
        this.field = resolveField(Objects.requireNonNull(entityType, "entityType"), name);
        this.association = association;
        AnnotatedElement annotations = declared == null ? field : declared;
        this.getter = annotations instanceof Method method ? accessible(method) : null;
        Column column = annotations.getAnnotation(Column.class);
        this.columnUpdatable = column == null || column.updatable();
        JoinColumn joinColumn = annotations.getAnnotation(JoinColumn.class);
        this.joinColumnUpdatable = joinColumn == null || joinColumn.updatable();
    }

    private static Method accessible(Method getter) {
        getter.setAccessible(true);
        return getter;
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
        if (getter != null) {
            try {
                return getter.invoke(entity);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("读取属性失败: " + getter, e);
            }
        }
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
