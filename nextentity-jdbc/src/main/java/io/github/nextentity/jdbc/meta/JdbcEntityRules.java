package io.github.nextentity.jdbc.meta;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
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
/// 规则见设计 §4.1，拒绝清单见 §4.3 #1–#14；只承认「字段访问」，getter 上的映射注解直接拒绝。
@SuppressWarnings("PMD.CouplingBetweenObjects") // 规则要逐条认注解，天然引用 JPA 注解族，与 JpaMeta 侧一致
final class JdbcEntityRules {

    private static final String ID_REQUIRED = "实体必须有且只有一个 @Id 字段: ";
    private static final String PROPERTY_ACCESS = "只支持字段访问（注解须在字段上）: ";
    private static final String INHERITANCE = "实体继承未支持: ";
    private static final String COMPOSITE_ID = "复合主键未支持: ";
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

    /// §4.3 #11 `@Inheritance`、#3 `@IdClass`、#10 `@Access(PROPERTY)`。
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
    }

    /// 字段发现：本类 + 连续的 `@MappedSuperclass` 父类（父类字段在前）；普通父类到此为止，
    /// 带 `@Entity` 的父类说明用了实体继承 → §4.3 #11。
    static List<Field> fields(Class<?> type) {
        List<List<Field>> levels = new ArrayList<>();
        Class<?> current = type;
        while (current != null) {
            rejectPropertyAnnotations(current, type);
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

    /// 单个字段 → 属性：先拒绝不支持的注解组合，再分「被忽略的关联 / @ManyToOne / 标量列」。
    static JdbcEntityAttribute attribute(Class<?> type, Field field) {
        String path = type.getName() + "." + field.getName();
        rejectField(type, field, path);
        if (ignored(field)) {
            return JdbcColumns.ignoredAssociation(field);
        }
        return field.isAnnotationPresent(ManyToOne.class)
                ? association(field, path)
                : JdbcColumns.scalar(type, field, path);
    }

    /// §4.3 #3 复合主键、#7 `@Embedded`、#8 `@Convert`。
    private static void rejectField(Class<?> type, Field field, String path) {
        if (field.isAnnotationPresent(EmbeddedId.class)) {
            throw new UnsupportedOperationException(COMPOSITE_ID + type.getName());
        }
        if (field.isAnnotationPresent(Embedded.class) || field.getType().isAnnotationPresent(Embeddable.class)) {
            throw new UnsupportedOperationException("@Embedded 未支持: " + path);
        }
        if (field.isAnnotationPresent(Convert.class)) {
            throw new UnsupportedOperationException("@Convert 未支持: " + path);
        }
    }

    /// 无 `@Id` 与多 `@Id` 共用一条消息（§4.3 #2）。
    static IllegalArgumentException idMissing(Class<?> type) {
        return new IllegalArgumentException(ID_REQUIRED + type.getName());
    }

    /// 多个 `@Version` 按设计 §4.3 #6 走同一句文案。
    static UnsupportedOperationException duplicateVersion(Class<?> type, Field field) {
        return new UnsupportedOperationException(
                "@Version 只支持 Integer / Long: " + type.getName() + "." + field.getName());
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

    /// getter 上有映射注解 → §4.3 #10：注解必须写在字段上。
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

    /// §4.3 #9：集合 / 一对一关联字段是唯一「静默忽略」项——不进 `attributes()`、不参与任何 SQL。
    private static boolean ignored(Field field) {
        return field.isAnnotationPresent(ElementCollection.class)
                || field.isAnnotationPresent(OneToMany.class)
                || field.isAnnotationPresent(ManyToMany.class)
                || field.isAnnotationPresent(OneToOne.class);
    }

    /// `@ManyToOne` 必须带 `@JoinColumn(name)`：没有 FK 列名就无法渲染 JOIN 与写入跳过规则。
    private static JdbcEntityAttribute association(Field field, String path) {
        JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
        if (joinColumn == null || JdbcNames.bare(joinColumn.name()).isEmpty()) {
            throw new UnsupportedOperationException("@ManyToOne 缺少 @JoinColumn(name): " + path);
        }
        JdbcColumns.rejectReferencedColumn(field, path, joinColumn.referencedColumnName());
        return JdbcColumns.association(field, joinColumn.name(), joinColumn.referencedColumnName(), path);
    }
}
