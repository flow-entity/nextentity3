package io.github.nextentity.jdbc.meta;

import io.github.nextentity.core.meta.EntityAttribute;
import jakarta.persistence.AttributeConverter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Objects;

/// `EntityAttribute` 的注解扫描实现：一个字段的读写句柄 + SQL 侧需要的列信息。
///
/// 与 JPA 侧（`JpaEntityAttribute`）不同，这里不借元模型，值类型 / 列名 / 枚举存储都在解析期算好，
/// 渲染与读出只读字段，不再碰注解。
///
/// 属性元数据按契约面平铺成读取方法（列名 / 主键 / 版本 / 枚举 / 关联 / quoted）；冲突只在于方法个数，
/// 拆成多个对象反而把渲染与写侧 12 个调用点变成链式跳转，故按单对象保留。
@SuppressWarnings({"PMD.TooManyMethods", "PMD.ExcessiveParameterListSix"})
public final class JdbcEntityAttribute implements EntityAttribute {

    private static final String ENTITY = "entity";

    /// 非嵌入属性共享同一个空列表：`List.of()` 每次调用都新建实例，但内容恒等，抽出来避免重复的无意义变异体。
    private static final List<JdbcEntityAttribute> NO_COLUMNS = List.of();

    private final Field field;
    private final Column column;
    private final Association association;
    private final Field container;
    private final Class<?> embeddedType;
    private final Constructor<?> containerConstructor;
    private final List<JdbcEntityAttribute> embeddedColumns;

    JdbcEntityAttribute(Field field, Column column, Association association) {
        this(field, column, association, null, null, null, NO_COLUMNS);
    }

    /// 扁平化后的嵌入字段：`field` 是嵌入类型的字段，`container` 是外层实体的 `@Embedded` 字段。
    JdbcEntityAttribute(Field field, Column column, Field container, Constructor<?> containerConstructor) {
        this(field, column, null, container, null, containerConstructor, NO_COLUMNS);
    }

    /// `@Embedded` 属性本身：`column` / `association` 都为 `null`，列信息在 `embeddedColumns` 里。
    JdbcEntityAttribute(Field field, Class<?> embeddedType, List<JdbcEntityAttribute> embeddedColumns) {
        this(field, null, null, null, embeddedType, null, embeddedColumns);
    }

    private JdbcEntityAttribute(
            Field field,
            Column column,
            Association association,
            Field container,
            Class<?> embeddedType,
            Constructor<?> containerConstructor,
            List<JdbcEntityAttribute> embeddedColumns) {
        this.field = field;
        this.field.setAccessible(true);
        this.column = column;
        this.association = association;
        this.container = container;
        this.embeddedType = embeddedType;
        this.containerConstructor = containerConstructor;
        this.embeddedColumns = embeddedColumns;
    }

    /// 把扁平化后的嵌入列绑定到外层 `@Embedded` 字段：读写经 `container` 中转，
    /// 外层对象里的嵌入实例为 `null` 时按需创建（写侧遇到非 `null` 值才创建，读侧只读不建）。
    JdbcEntityAttribute withContainer(Field container, Constructor<?> containerConstructor) {
        return new JdbcEntityAttribute(field, column, null, container, null, containerConstructor, NO_COLUMNS);
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
            if (container == null) {
                return field.get(entity);
            }
            Object holder = container.get(entity);
            return holder == null ? null : field.get(holder);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("读取字段失败: " + field, e);
        }
    }

    @Override
    public void set(Object entity, Object value) {
        Objects.requireNonNull(entity, ENTITY);
        try {
            if (container == null) {
                field.set(entity, value);
                return;
            }
            Object holder = container.get(entity);
            if (holder == null) {
                if (value == null) {
                    return;
                }
                holder = containerConstructor.newInstance();
                container.set(entity, holder);
            }
            field.set(holder, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("写入字段失败: " + field, e);
        }
    }

    /// 列名（标量属性）；关联属性与嵌入属性返回 `null`——FK 列名走 [#joinColumn()]。
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

    /// 是否自增主键（`@GeneratedValue(strategy = IDENTITY/AUTO)`）：INSERT 时不绑该列，写后回填。
    public boolean isGenerated() {
        return column != null && column.generated();
    }

    /// 标量列是否出现在 INSERT 的列集里（`@Column(insertable = false)` → 否，读回不受影响）。
    public boolean isColumnInsertable() {
        return column != null && column.insertable();
    }

    /// 标量列是否出现在 UPDATE 的 SET 子句里（`@Column(updatable = false)` → 否）。
    public boolean isColumnUpdatable() {
        return column != null && column.updatable();
    }

    /// 是否 `@Embedded` 属性：本身不对应一列，[#embeddedColumns()] 是扁平化后的列属性。
    public boolean isEmbedded() {
        return embeddedType != null;
    }

    /// 嵌入类型的 Java 类；非嵌入属性返回 `null`。
    public Class<?> embeddedTarget() {
        return embeddedType;
    }

    /// 嵌入属性扁平化后的列属性（按嵌入类型字段声明序）；非嵌入属性返回空列表。
    public List<JdbcEntityAttribute> embeddedColumns() {
        return embeddedColumns;
    }

    /// 按嵌入类型里的字段名取扁平列属性；未知名字返回 `null`。
    public JdbcEntityAttribute embeddedColumn(String name) {
        for (JdbcEntityAttribute embedded : embeddedColumns) {
            if (embedded.name().equals(name)) {
                return embedded;
            }
        }
        return null;
    }

    /// 是否 `@ManyToOne` / `@OneToOne` 关联属性。§4.3 #9 被忽略的关联也返回 `true`，靠 [joinColumn()]
    /// 是否为 `null` 区分。
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

    /// FK 列是否出现在 INSERT 的列集里（`@JoinColumn(insertable = false)` → 否）。
    public boolean isJoinColumnInsertable() {
        return association != null && association.joinColumnInsertable();
    }

    /// FK 列是否出现在 UPDATE 的 SET 子句里（`@JoinColumn(updatable = false)` → 否）。
    public boolean isJoinColumnUpdatable() {
        return association != null && association.joinColumnUpdatable();
    }

    /// 关联对象的 `@Id` 值（写关联时作为 FK 值绑定）；`null` 关联返回 `null`。
    ///
    /// 调用方保证本属性是关联属性（[#isAssociation()] 为真）：关联目标的 `@Id` 在扫描期必已解析，
    /// 因此这里只需处理「关联对象本身为 null」这一种输入。
    public Object associatedId(Object associated) {
        if (associated == null) {
            return null;
        }
        try {
            return association.idField().get(associated);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("读取关联主键失败: " + field, e);
        }
    }

    /// `@JoinColumn(referencedColumnName)` 是否用反引号包裹过（R5 校验时已剥引号比较，这里只记录形态）。
    public boolean isReferencedColumnQuoted() {
        return association != null && association.referencedColumnQuoted();
    }

    /// 枚举存储形态；非枚举列（含 `@Convert` 列）返回 [EnumStorage#NONE]。
    public EnumStorage enumStorage() {
        return column == null ? EnumStorage.NONE : column.storage();
    }

    /// `@Convert` 的转换器实例；没有 `@Convert` 返回 `null`。
    public AttributeConverter<Object, Object> converter() {
        return column == null ? null : column.converter();
    }

    /// `@Convert` 转换器的数据库类型（`AttributeConverter` 的第二个类型参数）；没有 `@Convert` 返回 `null`。
    public Class<?> databaseType() {
        return column == null ? null : column.databaseType();
    }

    /// 标量列的映射信息：列名已解析（`@Column(name)` → 字段名）。
    ///
    /// @param name       列名（已剥反引号）
    /// @param id         是否 `@Id`
    /// @param version    是否 `@Version`
    /// @param generated  是否 `@GeneratedValue`
    /// @param insertable 是否进 INSERT 列集（`@Column(insertable = false)` → false）
    /// @param updatable  是否进 UPDATE SET 子句（`@Column(updatable = false)` → false）
    /// @param storage    枚举存储形态
    /// @param quoted     注解里是否用反引号标记过，渲染时是否需要 `JdbcDialect#quote`
    /// @param converter  `@Convert` 转换器实例（无则 `null`）
    /// @param databaseType 转换器的数据库类型（无 `@Convert` 则 `null`）
    record Column(
            String name,
            boolean id,
            boolean version,
            boolean generated,
            boolean insertable,
            boolean updatable,
            EnumStorage storage,
            boolean quoted,
            AttributeConverter<Object, Object> converter,
            Class<?> databaseType) {}

    /// 关联列的映射信息：`joinColumn == null` 表示 §4.3 #9 被忽略的集合 / `mappedBy` 反侧字段。
    ///
    /// @param target               关联目标 Java 类型
    /// @param idField              目标 `@Id` 字段句柄（写关联时取 FK 值）；被忽略的关联为 `null`
    /// @param joinColumn           FK 列名（已剥反引号）；被忽略的关联为 `null`
    /// @param joinColumnQuoted     FK 列名注解里是否用反引号标记过
    /// @param referencedColumnQuoted `referencedColumnName` 注解里是否用反引号标记过
    /// @param joinColumnInsertable FK 列是否进 INSERT（`@JoinColumn(insertable = false)` → false）
    /// @param joinColumnUpdatable  FK 列是否进 UPDATE SET
    record Association(
            Class<?> target,
            Field idField,
            String joinColumn,
            boolean joinColumnQuoted,
            boolean referencedColumnQuoted,
            boolean joinColumnInsertable,
            boolean joinColumnUpdatable) {}
}
