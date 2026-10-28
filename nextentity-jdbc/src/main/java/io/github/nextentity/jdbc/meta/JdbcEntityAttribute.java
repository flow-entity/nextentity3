package io.github.nextentity.jdbc.meta;

import io.github.nextentity.core.meta.EntityAttribute;
import java.lang.reflect.Field;
import java.util.Objects;

/// `EntityAttribute` 的注解扫描实现：一个字段的读写句柄 + SQL 侧需要的列信息。
///
/// 与 JPA 侧（`JpaEntityAttribute`）不同，这里不借元模型，值类型 / 列名 / 枚举存储都在解析期算好，
/// 渲染与读出只读字段，不再碰注解。
///
/// 属性元数据按契约面平铺成读取方法（列名 / 主键 / 版本 / 枚举 / 关联 / quoted）；冲突只在于方法个数，
/// 拆成多个对象反而把渲染与写侧 12 个调用点变成链式跳转，故按单对象保留。
@SuppressWarnings("PMD.TooManyMethods")
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

    /// 列名是否需要在 SQL 中引用（`@Column(name)` 用反引号包裹过）：引用形态由 `JdbcDialect#quote` 决定。
    public boolean isColumnQuoted() {
        return column != null && column.quoted();
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

    /// FK 列名是否需要在 SQL 中引用（`@JoinColumn(name)` 用反引号包裹过）。
    public boolean isJoinColumnQuoted() {
        return association != null && association.joinColumnQuoted();
    }

    /// `@JoinColumn(referencedColumnName)` 是否用反引号包裹过（R5 校验时已剥引号比较，这里只记录形态）。
    public boolean isReferencedColumnQuoted() {
        return association != null && association.referencedColumnQuoted();
    }

    /// 枚举存储形态；非枚举列返回 [EnumStorage#NONE]。
    public EnumStorage enumStorage() {
        return column == null ? EnumStorage.NONE : column.storage();
    }

    /// 标量列的映射信息：列名已解析（`@Column(name)` → 字段名）。
    ///
    /// @param name      列名（已剥反引号）
    /// @param id        是否 `@Id`
    /// @param version   是否 `@Version`
    /// @param generated 是否 `@GeneratedValue(strategy = IDENTITY)`
    /// @param storage   枚举存储形态
    /// @param quoted    注解里是否用反引号标记过，渲染时是否需要 `JdbcDialect#quote`
    record Column(String name, boolean id, boolean version, boolean generated, EnumStorage storage, boolean quoted) {}

    /// 关联列的映射信息：`joinColumn == null` 表示 §4.3 #9 被忽略的集合 / 一对一字段。
    ///
    /// @param target            关联目标 Java 类型
    /// @param joinColumn              FK 列名（已剥反引号）；被忽略的关联为 `null`
    /// @param joinColumnQuoted       FK 列名注解里是否用反引号标记过
    /// @param referencedColumnQuoted `referencedColumnName` 注解里是否用反引号标记过
    record Association(Class<?> target, String joinColumn, boolean joinColumnQuoted, boolean referencedColumnQuoted) {}
}
