package io.github.nextentity.jdbc.meta;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Version;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// §4.3 #7 `@Embedded`：把嵌入类型的标量字段扁平化成列属性。
///
/// 列名默认 `<@Embedded 字段名>_<嵌套字段名>`（与 JPA 默认命名一致、无表前缀），`@AttributeOverride` 覆盖；
/// 嵌套 `@Embedded`、嵌入内的 `@Id` / `@Version` / `@GeneratedValue` / 关联本轮显式拒绝——扁平化层面
/// 表达不了这些形态，静默忽略会让实体映射出一张缺列的表。
final class JdbcEmbedded {

    private JdbcEmbedded() {}

    /// `@Embedded` 字段 → 属性：外层属性本身不对应列，列属性在 `embeddedColumns()` 里。
    static JdbcEntityAttribute embedded(Field field, String path) {
        Class<?> embeddable = field.getType();
        if (!embeddable.isAnnotationPresent(Embeddable.class)) {
            throw new UnsupportedOperationException(
                    "@Embedded 目标缺少 @Embeddable: " + path + ": " + embeddable.getName());
        }
        Constructor<?> constructor = constructor(embeddable, path);
        Map<String, String> overrides = overrides(field, path);
        List<JdbcEntityAttribute> columns = new ArrayList<>();
        for (Field nested : embeddable.getDeclaredFields()) {
            if (JdbcColumns.excluded(nested)) {
                continue;
            }
            String nestedPath = path + "." + nested.getName();
            rejectNested(nested, nestedPath);
            String defaultColumn = field.getName() + "_" + nested.getName();
            columns.add(
                    JdbcColumns.scalar(embeddable, nested, nestedPath, overrides.get(nested.getName()), defaultColumn)
                            .withContainer(field, constructor));
        }
        return new JdbcEntityAttribute(field, embeddable, columns);
    }

    private static Constructor<?> constructor(Class<?> embeddable, String path) {
        try {
            Constructor<?> constructor = embeddable.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor;
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("嵌入类型缺少无参构造: " + path + ": " + embeddable.getName(), e);
        }
    }

    /// `@AttributeOverride` → 嵌套字段名到列名；嵌套路径（`a.b`）本轮不支持。
    private static Map<String, String> overrides(Field field, String path) {
        Map<String, String> overrides = new HashMap<>();
        for (AttributeOverride override : field.getAnnotationsByType(AttributeOverride.class)) {
            String name = override.name();
            if (name.isEmpty()) {
                throw new UnsupportedOperationException("@AttributeOverride 缺少属性名: " + path);
            }
            if (name.indexOf('.') >= 0) {
                throw new UnsupportedOperationException("嵌套 @AttributeOverride 未支持: " + path + "." + name);
            }
            overrides.put(name, override.column().name());
        }
        return overrides;
    }

    /// 嵌入类型内部只允许标量列：主键 / 版本 / 生成键 / 嵌套嵌入 / 关联在扁平化层面无法表达。
    @SuppressWarnings("PMD.CyclomaticComplexity") // 嵌入类型内被拒的注解形态是穷举表（主键 / 版本 / 生成键 / 嵌套 / 关联）
    private static void rejectNested(Field field, String path) {
        if (field.isAnnotationPresent(Id.class)) {
            throw new UnsupportedOperationException("嵌入属性内不支持 @Id: " + path);
        }
        if (field.isAnnotationPresent(Version.class)) {
            throw new UnsupportedOperationException("嵌入属性内不支持 @Version: " + path);
        }
        if (field.isAnnotationPresent(GeneratedValue.class)) {
            throw new UnsupportedOperationException("嵌入属性内不支持 @GeneratedValue: " + path);
        }
        if (field.isAnnotationPresent(Embedded.class) || field.getType().isAnnotationPresent(Embeddable.class)) {
            throw new UnsupportedOperationException("嵌套 @Embedded 未支持: " + path);
        }
        if (field.isAnnotationPresent(ManyToOne.class)
                || field.isAnnotationPresent(OneToOne.class)
                || field.isAnnotationPresent(OneToMany.class)
                || field.isAnnotationPresent(ManyToMany.class)
                || field.isAnnotationPresent(ElementCollection.class)) {
            throw new UnsupportedOperationException("嵌入属性内的关联未支持: " + path);
        }
    }
}
