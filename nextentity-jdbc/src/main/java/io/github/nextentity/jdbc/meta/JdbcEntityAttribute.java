package io.github.nextentity.jdbc.meta;

import io.github.nextentity.core.meta.EntityAttribute;
import java.lang.reflect.Field;
import java.util.Objects;

/// `EntityAttribute` 的注解扫描实现：一个字段的读写句柄 + SQL 侧需要的列信息。
///
/// 与 JPA 侧（`JpaEntityAttribute`）不同，这里不借元模型，值类型 / 列名 / 枚举存储都在解析期算好，
/// 渲染与读出只读字段，不再碰注解。
public final class JdbcEntityAttribute implements EntityAttribute {

    private static final String ENTITY = "entity";

    private final Field field;
    private final Column column;
    private final Association association;

    JdbcEntityAttribute(Field field, Column column, Association association) {
        this.field = field;
        this.field.setAccessible(true);
        this.column = column;
        this.association = association;
    }

    @Override
    public String name() {
        return field.getName();
    }

    @Override
    public Class<?> type() {
        return field.getType();
    }

    @Override
    public Object get(Object entity) {
        Objects.requireNonNull(entity, ENTITY);
        try {
            return field.get(entity);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("读取字段失败: " + field, e);
        }
    }

    @Override
    public void set(Object entity, Object value) {
        Objects.requireNonNull(entity, ENTITY);
        try {
            field.set(entity, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("写入字段失败: " + field, e);
        }
    }

    /// 列名（标量属性）；关联属性返回 `null`——FK 列名走 [#joinColumn()]。
    public String column() {
        return column == null ? null : column.name();
    }

    /// 是否主键。
    public boolean isId() {
        return column != null && column.id();
    }

    /// 是否 `@Version`。
    public boolean isVersion() {
        return column != null && column.version();
    }

    /// 是否自增主键（`@GeneratedValue(strategy = IDENTITY)`）：INSERT 时不绑该列，写后回填。
    public boolean isGenerated() {
        return column != null && column.generated();
    }

    /// 是否 `@ManyToOne` 关联属性。§4.3 #9 被忽略的关联也返回 `true`，靠 [joinColumn()] 是否为 `null` 区分。
    public boolean isAssociation() {
        return association != null;
    }

    /// 关联目标实体的 Java 类型；标量属性返回 `null`。只存 `Class`，目标表 / 主键在渲染期经
    /// `JdbcMeta` 惰性解析（避免 `computeIfAbsent` 递归）。
    public Class<?> associationTarget() {
        return association == null ? null : association.target();
    }

    /// FK 列名；标量属性与被忽略的关联返回 `null`（后者不可渲染）。
    public String joinColumn() {
        return association == null ? null : association.joinColumn();
    }

    /// 枚举存储形态；非枚举列返回 [EnumStorage#NONE]。
    public EnumStorage enumStorage() {
        return column == null ? EnumStorage.NONE : column.storage();
    }

    /// 标量列的映射信息：列名已解析（`@Column(name)` → 字段名）。
    ///
    /// @param name      列名
    /// @param id        是否 `@Id`
    /// @param version   是否 `@Version`
    /// @param generated 是否 `@GeneratedValue(strategy = IDENTITY)`
    /// @param storage   枚举存储形态
    record Column(String name, boolean id, boolean version, boolean generated, EnumStorage storage) {}

    /// 关联列的映射信息：`joinColumn == null` 表示 §4.3 #9 被忽略的集合 / 一对一字段。
    ///
    /// @param target     关联目标 Java 类型
    /// @param joinColumn FK 列名；被忽略的关联为 `null`
    record Association(Class<?> target, String joinColumn) {}
}
