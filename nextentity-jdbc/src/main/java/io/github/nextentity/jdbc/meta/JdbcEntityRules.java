package io.github.nextentity.jdbc.meta;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.AssociationOverride;
import jakarta.persistence.AssociationOverrides;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Inheritance;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/// 注解层面的规则：哪些注解组合直接拒绝、哪些字段被静默忽略、以及表名 / 无参构造的解析。
///
/// 映射规则只承认「字段访问」，getter 上的映射注解直接拒绝。
@SuppressWarnings({"PMD.CouplingBetweenObjects", "PMD.TooManyMethods"}) // 规则要逐条认注解，天然引用 JPA 注解族，与 JpaMeta
// 侧一致；类级拒绝本身就是一串并列规则，拆类只会把同族规则散开
final class JdbcEntityRules {

    private static final String ID_REQUIRED = "实体必须有且只有一个 @Id 字段: ";
    private static final String PROPERTY_ACCESS = "只支持字段访问（注解须在字段上）: ";
    private static final String TABLE_QUALIFIER_UNSUPPORTED = "@Table(schema/catalog) 未支持: ";
    private static final String CLASS_LEVEL_OVERRIDE_UNSUPPORTED = "类级 @AttributeOverride/@AssociationOverride 未支持: ";
    private static final String CLASS_LEVEL_OVERRIDE_HINT = "（请声明到嵌入字段或关联字段上）";
    private static final String INHERITANCE = "实体继承未支持: ";
    static final String COMPOSITE_ID = "复合主键未支持: ";
    static final String VERSION_UNSUPPORTED = "@Version 只支持 int / long / short（含包装类型）: ";
    static final String VERSION_CONVERT_UNSUPPORTED = "@Version 不支持 @Convert: ";
    static final String VERSION_INSERTABLE_UNSUPPORTED =
            "@Version 列不支持 @Column(insertable=false)（版本初值由本层写入，insertable 只约束业务列）: ";
    private static final List<Class<? extends Annotation>> MAPPING_ANNOTATIONS = List.of(
            Id.class,
            Version.class,
            Column.class,
            Enumerated.class,
            ManyToOne.class,
            OneToOne.class,
            OneToMany.class,
            ManyToMany.class,
            ElementCollection.class,
            Embedded.class,
            EmbeddedId.class,
            Convert.class,
            JoinColumn.class,
            GeneratedValue.class);

    private JdbcEntityRules() {}

    /// 类级拒绝：`@Inheritance`、`@IdClass`、`@Access(PROPERTY)`、`@Table(schema/catalog)`、类级覆盖。
    static void rejectClassLevel(Class<?> type) {
        if (type.isAnnotationPresent(Inheritance.class)) {
            throw new UnsupportedOperationException(INHERITANCE + type.getName());
        }
        if (type.isAnnotationPresent(IdClass.class)) {
            throw new UnsupportedOperationException(COMPOSITE_ID + type.getName());
        }
        Access access = type.getAnnotation(Access.class);
        if (access != null && access.value() == AccessType.PROPERTY) {
            throw new UnsupportedOperationException(PROPERTY_ACCESS + type.getName());
        }
        rejectTableQualifier(type);
        rejectClassLevelOverrides(type);
    }

    /// 类级（TYPE）`@AttributeOverride` / `@AssociationOverride`（点号路径形态）拒绝：本后端只建模
    /// 成员级覆盖，类级形态既不读也不该静默降级——降级的后果是列名回落到默认列名，按 JPA 语义
    /// 建的表在首次写入时报 `Column not found`，报错点离原因很远（JPA 后端经 Hibernate 支持该形态，
    /// 不拒就是两后端分叉）。未实现的映射一律 fail-fast。
    ///
    /// 两处调用：本方法（实体类、`@Embeddable` 类）与 [JdbcEntityRules#fields] 的逐层循环
    /// （`@MappedSuperclass` 链上声明时，只有逐层循环看得到）。
    private static void rejectClassLevelOverrides(Class<?> type) {
        if (type.isAnnotationPresent(AttributeOverride.class)
                || type.isAnnotationPresent(AttributeOverrides.class)
                || type.isAnnotationPresent(AssociationOverride.class)
                || type.isAnnotationPresent(AssociationOverrides.class)) {
            throw new UnsupportedOperationException(
                    CLASS_LEVEL_OVERRIDE_UNSUPPORTED + type.getName() + CLASS_LEVEL_OVERRIDE_HINT);
        }
    }

    /// `@Table(schema/catalog)` 拒绝：与其它类级拒绝同类——本后端的表名只取 `@Table(name)`，
    /// schema / catalog 从不进 SQL，静默丢弃会让语句打到默认 schema 的同名表上（写入落到别处、
    /// 查询读到别的数据，全程不报错）。未实现的映射一律 fail-fast，不静默降级。
    private static void rejectTableQualifier(Class<?> type) {
        Table table = type.getAnnotation(Table.class);
        if (table != null && !(table.schema().isEmpty() && table.catalog().isEmpty())) {
            throw new UnsupportedOperationException(TABLE_QUALIFIER_UNSUPPORTED + type.getName());
        }
    }

    /// 字段发现：本类 + 连续的 `@MappedSuperclass` 父类（父类字段在前）；普通父类到此为止，
    /// 带 `@Entity` 的父类说明用了实体继承，按继承拒绝。
    static List<Field> fields(Class<?> type) {
        List<List<Field>> levels = new ArrayList<>();
        Class<?> current = type;
        while (current != null) {
            rejectPropertyAnnotations(current, type);
            rejectClassLevelOverrides(current);
            levels.add(declaredFields(current));
            Class<?> parent = current.getSuperclass();
            if (parent == null || parent == Object.class) {
                break;
            }
            if (parent.isAnnotationPresent(Entity.class)) {
                throw new UnsupportedOperationException(INHERITANCE + type.getName());
            }
            if (!parent.isAnnotationPresent(MappedSuperclass.class)) {
                break;
            }
            current = parent;
        }
        Collections.reverse(levels);
        List<Field> fields = new ArrayList<>();
        levels.forEach(fields::addAll);
        return fields;
    }

    /// 单个字段 → 属性：先拒绝不支持的注解组合，再分「@Embedded / 被忽略的关联 / @ManyToOne / @OneToOne / 标量列」。
    static JdbcEntityAttribute attribute(Class<?> type, Field field) {
        String path = type.getName() + "." + field.getName();
        rejectField(type, field);
        if (field.isAnnotationPresent(Embedded.class) || field.getType().isAnnotationPresent(Embeddable.class)) {
            return JdbcEmbedded.embedded(field, path);
        }
        if (ignored(field)) {
            return JdbcColumns.ignoredAssociation(field);
        }
        return isAssociation(field) ? association(field, path) : JdbcColumns.scalar(type, field, path);
    }

    /// 复合主键（`@EmbeddedId`）拒绝；`@Embedded` / `@Convert` 已支持，规则在 [JdbcEmbedded] 与 [JdbcColumns]。
    private static void rejectField(Class<?> type, Field field) {
        if (field.isAnnotationPresent(EmbeddedId.class)) {
            throw new UnsupportedOperationException(COMPOSITE_ID + type.getName());
        }
    }

    /// 无 `@Id` 与多 `@Id` 共用一条消息。
    static IllegalArgumentException idMissing(Class<?> type) {
        return new IllegalArgumentException(ID_REQUIRED + type.getName());
    }

    /// 多个 `@Version` 与不支持的版本类型共用一句文案。
    static UnsupportedOperationException duplicateVersion(Class<?> type, Field field) {
        return new UnsupportedOperationException(VERSION_UNSUPPORTED + type.getName() + "." + field.getName());
    }

    private static List<Field> declaredFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (!JdbcColumns.excluded(field)) {
                fields.add(field);
            }
        }
        return fields;
    }

    /// getter 上有映射注解：本后端只支持字段访问，注解必须写在字段上。
    private static void rejectPropertyAnnotations(Class<?> current, Class<?> type) {
        for (Method method : current.getDeclaredMethods()) {
            if (method.getParameterCount() == 0
                    && !Modifier.isStatic(method.getModifiers())
                    && (method.getName().startsWith("get") || method.getName().startsWith("is"))
                    && mapped(method)) {
                throw new UnsupportedOperationException(PROPERTY_ACCESS + type.getName());
            }
        }
    }

    private static boolean mapped(Method method) {
        return MAPPING_ANNOTATIONS.stream().anyMatch(method::isAnnotationPresent);
    }

    /// 集合关联与 `@OneToOne(mappedBy=...)` 是唯一「静默忽略」项——不进 `attributes()`、
    /// 不参与任何 SQL；`mappedBy` 非空表示反侧没有 FK 列，写侧与 JOIN 都无处落列。
    private static boolean ignored(Field field) {
        OneToOne oneToOne = field.getAnnotation(OneToOne.class);
        return field.isAnnotationPresent(ElementCollection.class)
                || field.isAnnotationPresent(OneToMany.class)
                || field.isAnnotationPresent(ManyToMany.class)
                || (oneToOne != null && !oneToOne.mappedBy().isEmpty());
    }

    private static boolean isAssociation(Field field) {
        return field.isAnnotationPresent(ManyToOne.class) || field.isAnnotationPresent(OneToOne.class);
    }

    /// `@ManyToOne` / `@OneToOne` 的 FK 列：`@JoinColumn(name)` 优先，缺省按 JPA 默认命名
    /// `<字段名>_<被引用列名>` 推断（被引用列 = 目标 `@Id` 的实际列名）。
    ///
    /// `@JoinColumn` 是**可重复注解**：`@JoinColumns(@JoinColumn(...))`（容器写法，单列时与单列写法同义）
    /// 也要读——`getAnnotation(JoinColumn.class)` 对容器写法返回 `null`，会把容器声明的列名 / 只读标志
    /// 静默丢掉（列名回落默认命名、`updatable=false` 的外键照常进 SET）。复合外键（>1 列）本层只建模
    /// 单列外键，元数据期显式拒绝而不是静默取第一列（文案与 JPA 后端逐字一致）。
    private static JdbcEntityAttribute association(Field field, String path) {
        JoinColumn[] joinColumns = field.getAnnotationsByType(JoinColumn.class);
        if (joinColumns.length > 1) {
            throw new UnsupportedOperationException(
                    "复合外键关联未支持: " + path + "（@JoinColumns 列数=" + joinColumns.length + "，本层只建模单列外键）");
        }
        JoinColumn joinColumn = joinColumns.length == 0 ? null : joinColumns[0];
        String declared = joinColumn == null ? "" : JdbcNames.bare(joinColumn.name(), path);
        String joinColumnName =
                declared.isEmpty() ? field.getName() + "_" + JdbcColumns.idColumn(field.getType()) : declared;
        boolean quoted = !declared.isEmpty() && JdbcNames.isQuoted(joinColumn.name());
        String referencedColumnName = joinColumn == null ? "" : joinColumn.referencedColumnName();
        JdbcColumns.rejectReferencedColumn(field, path, referencedColumnName);
        return JdbcColumns.association(
                field,
                joinColumnName,
                quoted,
                referencedColumnName,
                joinColumn != null,
                joinColumn == null || joinColumn.insertable(),
                joinColumn == null || joinColumn.updatable(),
                path);
    }
}
