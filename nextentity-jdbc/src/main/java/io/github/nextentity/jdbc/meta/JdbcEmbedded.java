package io.github.nextentity.jdbc.meta;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// `@Embedded`：把嵌入类型的标量字段扁平化成列属性。
///
/// 列名默认取嵌入类型的字段名（与 Hibernate/JPA 默认命名一致），`@AttributeOverride` 覆盖；
/// 嵌套 `@Embedded`、容器字段上的 `@Convert`、嵌入内的 `@Id` / `@Version` / `@GeneratedValue` / 关联本轮显式拒绝——
/// 扁平化层面表达不了这些形态，静默忽略会让实体映射出一张缺列的表。容器字段自身的
/// `@Id`（Hibernate 里是合法复合主键）/ `@Version` / `@GeneratedValue`（Hibernate 侧构建期错误）同样拒绝，
/// 否则注解被吞掉、实体映射出一张没有版本列的表。容器上的 `@Convert` 两种形态都拒：
/// 不带 `attributeName` 在 JPA 侧本身非法，带 `attributeName` 需要子属性寻址。
final class JdbcEmbedded {

    private JdbcEmbedded() {}

    /// `@Embedded` 字段 → 属性：外层属性本身不对应列，列属性在 `embeddedColumns()` 里。
    ///
    /// 字段发现与实体侧同规则（本类 + 连续的 `@MappedSuperclass` 父类，父类字段在前），
    /// 属性访问校验也下放到嵌入类型：`@Embeddable` 里的 getter 注解同样静默失效，必须拒绝。
    /// `@AttributeOverride` 必须逐条命中叶子列——拼错的属性名在 JPA 侧是元模型期错误，
    /// 这里静默忽略会让列名悄悄回落成默认值。
    static JdbcEntityAttribute embedded(Field field, String path) {
        Class<?> embeddable = field.getType();
        if (!embeddable.isAnnotationPresent(Embeddable.class)) {
            throw new UnsupportedOperationException(
                    "@Embedded 目标缺少 @Embeddable: " + path + ": " + embeddable.getName());
        }
        rejectContainerConvert(field, path);
        rejectContainerAnnotations(field, path);
        JdbcEntityRules.rejectClassLevel(embeddable);
        Constructor<?> constructor = constructor(embeddable, path);
        Map<String, Column> overrides = overrides(field, path);
        List<JdbcEntityAttribute> columns = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Field nested : JdbcEntityRules.fields(embeddable)) {
            String nestedPath = path + "." + nested.getName();
            if (!names.add(nested.getName())) {
                throw new UnsupportedOperationException("嵌入类型内字段名重复（父类字段被子类遮蔽）: " + path + "." + nested.getName());
            }
            rejectNested(nested, nestedPath);
            String defaultColumn = nested.getName();
            columns.add(JdbcColumns.scalar(
                            embeddable, nested, nestedPath, overrides.remove(nested.getName()), defaultColumn)
                    .withContainer(field, constructor));
        }
        if (!overrides.isEmpty()) {
            throw new UnsupportedOperationException("未命中的 @AttributeOverride: " + path + "."
                    + overrides.keySet().iterator().next());
        }
        return new JdbcEntityAttribute(field, embeddable, columns);
    }

    /// 容器字段上的 `@Convert` 两种形态都拒：不带 `attributeName` 在 JPA 侧本身非法，带 `attributeName`
    /// 要子属性寻址，扁平化层面无处安放——静默忽略会让列落成未转换的值。`disableConversion` 按 JPA 语义
    /// 等于「不转换」，放行。
    private static void rejectContainerConvert(Field field, String path) {
        Convert convert = field.getAnnotation(Convert.class);
        if (convert != null && !convert.disableConversion()) {
            throw new UnsupportedOperationException("@Embedded 字段上的 @Convert 未支持: " + path);
        }
    }

    /// 容器字段自身的主键 / 版本 / 生成键注解：容器属性没有 `Column` 形态（`JdbcEntityAttribute#column()`
    /// 为 `null`，`isId()` / `isVersion()` / `isGenerated()` 恒为 `false`），不拒绝就等于静默吞注解。
    ///
    /// `@Id @Embedded` 在 Hibernate 里是合法的复合主键，本层不支持复合主键，所以按 `COMPOSITE_ID` 拒绝
    /// （而不是「注解非法」）；`@Version` / `@GeneratedValue` 标在容器上在 Hibernate 侧是 `EntityManagerFactory`
    /// 构建期错误，这里同样拒绝。`@Version` 最危险：实体声明了版本列却不生成版本列，乐观锁静默失效。
    private static void rejectContainerAnnotations(Field field, String path) {
        if (field.isAnnotationPresent(Id.class)) {
            throw new UnsupportedOperationException(JdbcEntityRules.COMPOSITE_ID + path);
        }
        if (field.isAnnotationPresent(Version.class)) {
            throw new UnsupportedOperationException("@Embedded 字段上的 @Version 未支持: " + path);
        }
        if (field.isAnnotationPresent(GeneratedValue.class)) {
            throw new UnsupportedOperationException("@Embedded 字段上的 @GeneratedValue 未支持: " + path);
        }
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

    /// `@AttributeOverride` → 嵌套字段名到整份列映射（列名与 `insertable` / `updatable` 都以覆盖为准）；嵌套路径
    /// （`a.b`）本轮不支持。
    private static Map<String, Column> overrides(Field field, String path) {
        Map<String, Column> overrides = new LinkedHashMap<>();
        for (AttributeOverride override : field.getAnnotationsByType(AttributeOverride.class)) {
            String name = override.name();
            if (name.isEmpty()) {
                throw new UnsupportedOperationException("@AttributeOverride 缺少属性名: " + path);
            }
            if (name.indexOf('.') >= 0) {
                throw new UnsupportedOperationException("嵌套 @AttributeOverride 未支持: " + path + "." + name);
            }
            overrides.put(name, override.column());
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
