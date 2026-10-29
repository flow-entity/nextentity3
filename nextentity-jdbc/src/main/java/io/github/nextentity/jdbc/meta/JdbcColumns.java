package io.github.nextentity.jdbc.meta;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/// 属性构造规则：标量列的 #4/#5/#6/#12/#14，以及关联属性 / 被忽略关联的构造。
final class JdbcColumns {

    private JdbcColumns() {}

    /// 标量字段 → 列属性：先按 #4/#5/#6/#12/#14 拒绝，再算列名与枚举存储。
    static JdbcEntityAttribute scalar(Class<?> type, Field field, String path) {
        boolean id = field.isAnnotationPresent(Id.class);
        boolean version = field.isAnnotationPresent(Version.class);
        boolean generated = id && field.isAnnotationPresent(GeneratedValue.class);
        if (generated) {
            rejectGenerated(field, path);
        }
        if (version && !integral(field.getType())) {
            throw new UnsupportedOperationException("@Version 只支持 Integer / Long: " + path);
        }
        rejectColumnType(field, path);
        rejectColumnFlags(field, path);
        ColumnName column = columnName(field, path);
        return new JdbcEntityAttribute(
                field,
                new JdbcEntityAttribute.Column(
                        column.name(), id, version, generated, enumStorage(field), column.quoted()),
                null);
    }

    /// `@ManyToOne` 的 FK 列（带 `@JoinColumn(name)`）：反引号包裹的名字剥掉并记 quoted。
    static JdbcEntityAttribute association(Field field, String joinColumn, String referencedColumnName, String path) {
        return new JdbcEntityAttribute(
                field,
                null,
                new JdbcEntityAttribute.Association(
                        field.getType(),
                        JdbcNames.bare(joinColumn, path),
                        JdbcNames.isQuoted(joinColumn),
                        JdbcNames.isQuoted(referencedColumnName)));
    }

    /// §4.3 #9 被忽略的集合 / 一对一关联：可被 `attribute(name)` 解析，但不进 `attributes()`。
    static JdbcEntityAttribute ignoredAssociation(Field field) {
        return new JdbcEntityAttribute(
                field, null, new JdbcEntityAttribute.Association(field.getType(), null, false, false));
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
    private static String idColumn(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(Id.class)) {
                    Column column = field.getAnnotation(Column.class);
                    String name = column == null ? "" : JdbcNames.bare(column.name());
                    return name.isEmpty() ? field.getName() : name;
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

    /// §4.3 #4/#5：只支持 `@GeneratedValue(strategy = IDENTITY)` 的 `Integer` / `Long` 主键。
    private static void rejectGenerated(Field field, String path) {
        GeneratedValue generated = field.getAnnotation(GeneratedValue.class);
        if (generated.strategy() != GenerationType.IDENTITY) {
            throw new UnsupportedOperationException("只支持 @GeneratedValue(strategy=IDENTITY): " + path);
        }
        if (!integral(field.getType())) {
            throw new UnsupportedOperationException("生成键只支持 Integer / Long: " + path);
        }
    }

    /// §4.3 #12：白名单只有 `String` / `Integer` / `Long` / 枚举（`int` / `long` 供 `@Version` 原始类型列）。
    private static void rejectColumnType(Field field, String path) {
        Class<?> columnType = field.getType();
        boolean supported = columnType == String.class
                || columnType == Integer.class
                || columnType == int.class
                || columnType == Long.class
                || columnType == long.class
                || columnType.isEnum();
        if (!supported) {
            throw new UnsupportedOperationException("不支持的列类型: " + path + ": " + columnType.getName());
        }
    }

    private static void rejectColumnFlags(Field field, String path) {
        Column column = field.getAnnotation(Column.class);
        if (column != null && (!column.insertable() || !column.updatable())) {
            throw new UnsupportedOperationException("@Column(insertable/updatable=false) 未支持: " + path);
        }
    }

    private static boolean integral(Class<?> type) {
        return type == Integer.class || type == int.class || type == Long.class || type == long.class;
    }

    /// 列名：`@Column(name)` 非空优先（剥反引号），否则字段名。
    private static ColumnName columnName(Field field, String path) {
        Column column = field.getAnnotation(Column.class);
        String raw = column == null ? "" : column.name();
        String name = JdbcNames.bare(raw, path);
        if (name.isEmpty()) {
            return new ColumnName(field.getName(), false);
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
