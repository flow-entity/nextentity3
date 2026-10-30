package io.github.nextentity.jdbc.meta;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.util.Set;

/// 属性构造规则：标量列的 #4/#5/#6/#12/#14，以及关联属性 / 被忽略关联 / 默认 FK 列名的构造。
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CyclomaticComplexity", "PMD.ExcessiveParameterListSix"
}) // 规则面与参数面由 JPA 注解族决定，逐条注解处理；参数是反射解析上下文，合并成 record 只是搬运
final class JdbcColumns {

    private JdbcColumns() {}

    /// 标量字段 → 列属性：先按 #4/#5/#6/#12/#14 拒绝，再算列名、可写标志与枚举存储。
    static JdbcEntityAttribute scalar(Class<?> type, Field field, String path) {
        return scalar(type, field, path, null, field.getName());
    }

    /// 嵌入列的重载：`overrideColumn` 来自 `@AttributeOverride`（优先），`defaultColumn` 是 JPA 默认命名
    /// `<@Embedded 字段名>_<嵌套字段名>`；两者都缺时回落 `@Column(name)` / 字段名。
    static JdbcEntityAttribute scalar(
            Class<?> type, Field field, String path, String overrideColumn, String defaultColumn) {
        boolean id = field.isAnnotationPresent(Id.class);
        boolean version = field.isAnnotationPresent(Version.class);
        boolean generated = id && field.isAnnotationPresent(GeneratedValue.class);
        if (generated) {
            rejectGenerated(field, path);
        }
        if (version && !versionType(field.getType())) {
            throw new UnsupportedOperationException(JdbcEntityRules.VERSION_UNSUPPORTED + path);
        }
        Converter converter = converter(field, path);
        if (converter == null) {
            rejectColumnType(field, path);
        }
        Column flags = field.getAnnotation(Column.class);
        ColumnName column = columnName(field, path, overrideColumn, defaultColumn);
        return new JdbcEntityAttribute(
                field,
                new JdbcEntityAttribute.Column(
                        column.name(),
                        id,
                        version,
                        generated,
                        flags == null || flags.insertable(),
                        flags == null || flags.updatable(),
                        converter == null ? enumStorage(field) : EnumStorage.NONE,
                        column.quoted(),
                        converter == null ? null : converter.converter(),
                        converter == null ? null : converter.databaseType()),
                null);
    }

    /// `@ManyToOne` / `@OneToOne` 的 FK 列：列名已解析（缺省名由 [JdbcEntityRules] 按 JPA 默认命名推断），
    /// `insertable` / `updatable` 来自 `@JoinColumn`（缺省 true；examples 里的只读关联靠它们跳过写列）。
    static JdbcEntityAttribute association(
            Field field,
            String joinColumn,
            boolean joinColumnQuoted,
            String referencedColumnName,
            boolean joinColumnDeclared,
            boolean insertable,
            boolean updatable,
            String path) {
        return new JdbcEntityAttribute(
                field,
                null,
                new JdbcEntityAttribute.Association(
                        field.getType(),
                        idField(field.getType()),
                        JdbcNames.bare(joinColumn, path),
                        joinColumnQuoted,
                        joinColumnDeclared && JdbcNames.isQuoted(referencedColumnName),
                        insertable,
                        updatable));
    }

    /// §4.3 #9 被忽略的集合 / `mappedBy` 反侧关联：可被 `attribute(name)` 解析，但不进 `attributes()`。
    static JdbcEntityAttribute ignoredAssociation(Field field) {
        return new JdbcEntityAttribute(
                field,
                null,
                new JdbcEntityAttribute.Association(field.getType(), null, null, false, false, false, false));
    }

    /// R5：JOIN 固定按目标 `@Id` 列拼，`referencedColumnName` 只允许为空或等于目标 `@Id` 的实际列名
    /// （`@Column(name)` 优先、否则字段名，忽略大小写，反引号先剥）；其它值显式拒绝，避免静默拼出错误 JOIN。
    static void rejectReferencedColumn(Field field, String path, String referencedColumnName) {
        String referenced = JdbcNames.bare(referencedColumnName, path);
        if (referenced.isEmpty()) {
            return;
        }
        String idColumn = idColumn(field.getType());
        if (!referenced.equalsIgnoreCase(idColumn)) {
            throw new UnsupportedOperationException("@JoinColumn(referencedColumnName=" + referenced + ") 未支持: " + path
                    + "（目标 " + field.getType().getName() + " 的 @Id 列是 " + idColumn + "）");
        }
    }

    /// 目标实体的 `@Id` 实际列名（裸名），只读注解不建元数据，避免关联目标解析递归。
    static String idColumn(Class<?> type) {
        Field field = idField(type);
        Column column = field.getAnnotation(Column.class);
        String name = column == null ? "" : JdbcNames.bare(column.name());
        return name.isEmpty() ? field.getName() : name;
    }

    /// 目标实体的 `@Id` 字段句柄（写关联时从关联对象取 FK 值）；只读注解不建元数据。
    static Field idField(Class<?> type) {
        for (Class<?> current = type; current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(Id.class)) {
                    field.setAccessible(true);
                    return field;
                }
            }
        }
        throw JdbcEntityRules.idMissing(type);
    }

    /// 不参与映射的字段：静态 / final / 编译器合成 / `@Transient`（`final` 覆盖 version 原始类型等常量字段）。
    static boolean excluded(Field field) {
        return Modifier.isStatic(field.getModifiers())
                || Modifier.isFinal(field.getModifiers())
                || field.isSynthetic()
                || field.isAnnotationPresent(Transient.class);
    }

    /// §4.3 #4/#5：`IDENTITY` 与 `AUTO` 都按自增列落库（本后端方言集合都不做序列，AUTO 统一落 IDENTITY）；
    /// 生成键类型放宽到 JPA 允许的数值型 `short` / `int` / `long` / `BigInteger`（含包装）。
    private static void rejectGenerated(Field field, String path) {
        GeneratedValue generated = field.getAnnotation(GeneratedValue.class);
        if (generated.strategy() != GenerationType.IDENTITY && generated.strategy() != GenerationType.AUTO) {
            throw new UnsupportedOperationException("只支持 @GeneratedValue(strategy=IDENTITY/AUTO): " + path);
        }
        if (!generatedKeyType(field.getType())) {
            throw new UnsupportedOperationException("生成键只支持 short / int / long / BigInteger（含包装类型）: " + path);
        }
    }

    /// §4.3 #12：列类型白名单的唯一出处是 [JdbcTypes#supports(Class)]，拒绝文案给出转换器出路。
    private static void rejectColumnType(Field field, String path) {
        Class<?> columnType = field.getType();
        if (!JdbcTypes.supports(columnType)) {
            throw new UnsupportedOperationException(
                    "不支持的列类型: " + path + ": " + columnType.getName() + "（支持的列类型见 JdbcTypes；自定义类型请用 @Convert 或自定义实现）");
        }
    }

    private static final Set<Class<?>> VERSION_TYPES =
            Set.of(Integer.class, int.class, Long.class, long.class, Short.class, short.class);

    private static boolean versionType(Class<?> type) {
        return VERSION_TYPES.contains(type);
    }

    private static final Set<Class<?>> GENERATED_KEY_TYPES =
            Set.of(Integer.class, int.class, Long.class, long.class, Short.class, short.class, BigInteger.class);

    private static boolean generatedKeyType(Class<?> type) {
        return GENERATED_KEY_TYPES.contains(type);
    }

    /// §4.3 #6 `@Convert`：实例化 `AttributeConverter`，从泛型实参取数据库类型并要求落在列类型白名单里。
    /// `@Convert(disableConversion = true)` 按 JPA 语义整体关闭转换（本轮不实现 `autoApply`），按无转换器处理。
    @SuppressWarnings("PMD.CyclomaticComplexity") // 分支覆盖 @Convert 的四种声明形态（关闭转换 / 缺转换器 / attributeName / 正常）
    private static Converter converter(Field field, String path) {
        Convert annotation = field.getAnnotation(Convert.class);
        if (annotation == null || annotation.disableConversion()) {
            return null;
        }
        Class<?> converterType = annotation.converter();
        if (converterType == void.class || converterType == AttributeConverter.class) {
            if (annotation.attributeName().isEmpty()) {
                return null;
            }
            throw new UnsupportedOperationException("@Convert 缺少 converter: " + path);
        }
        if (!annotation.attributeName().isEmpty()) {
            throw new UnsupportedOperationException("@Convert(attributeName=...) 未支持: " + path);
        }
        AttributeConverter<Object, Object> converter = instantiate(converterType, path);
        Class<?> databaseType = databaseType(converterType, path);
        if (!JdbcTypes.supports(databaseType)) {
            throw new UnsupportedOperationException(
                    "@Convert 转换器的数据库类型未支持: " + path + ": " + databaseType.getName() + "（支持的列类型见 JdbcTypes）");
        }
        return new Converter(converter, databaseType);
    }

    @SuppressWarnings("unchecked") // 实例化后按 AttributeConverter<Object, Object> 调用，泛型实参已校验
    private static AttributeConverter<Object, Object> instantiate(Class<?> type, String path) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return (AttributeConverter<Object, Object>) constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("无法实例化 @Convert 转换器（需要无参构造）: " + path + ": " + type.getName(), e);
        }
    }

    /// 数据库类型 = `AttributeConverter<X, Y>` 的第二个类型实参；转换器经父类间接实现时沿父类找。
    private static Class<?> databaseType(Class<?> type, String path) {
        for (Class<?> current = type; current != Object.class; current = current.getSuperclass()) {
            for (Type declared : current.getGenericInterfaces()) {
                if (declared instanceof ParameterizedType parameterized
                        && parameterized.getRawType() == AttributeConverter.class
                        && parameterized.getActualTypeArguments()[1] instanceof Class<?> argument) {
                    return argument;
                }
            }
        }
        throw new UnsupportedOperationException(
                "无法确定 @Convert 转换器的数据库类型（须实现 AttributeConverter<X, Y>）: " + path + ": " + type.getName());
    }

    /// `@Convert` 的实例与数据库类型。
    private record Converter(AttributeConverter<Object, Object> converter, Class<?> databaseType) {}

    /// 列名：`@AttributeOverride` 优先，其次 `@Column(name)`（剥反引号），都没有时用默认命名。
    private static ColumnName columnName(Field field, String path, String overrideColumn, String defaultColumn) {
        Column column = field.getAnnotation(Column.class);
        String raw = overrideColumn != null ? overrideColumn : column == null ? "" : column.name();
        String name = JdbcNames.bare(raw, path);
        if (name.isEmpty()) {
            return new ColumnName(defaultColumn, false);
        }
        return new ColumnName(name, JdbcNames.isQuoted(raw));
    }

    /// 列名 + quoted 标志。
    private record ColumnName(String name, boolean quoted) {}

    /// 枚举存储：`@Enumerated(STRING)` → name；ORDINAL 或无注解（JPA 默认）→ ordinal。
    private static EnumStorage enumStorage(Field field) {
        if (!field.getType().isEnum()) {
            return EnumStorage.NONE;
        }
        Enumerated enumerated = field.getAnnotation(Enumerated.class);
        return enumerated != null && enumerated.value() == EnumType.STRING ? EnumStorage.NAME : EnumStorage.ORDINAL;
    }
}
