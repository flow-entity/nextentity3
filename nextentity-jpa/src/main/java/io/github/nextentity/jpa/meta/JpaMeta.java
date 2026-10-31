package io.github.nextentity.jpa.meta;

import jakarta.persistence.AssociationOverride;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.Attribute.PersistentAttributeType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// JPA 元模型入口：实体元数据解析 + 关联判定。
///
/// 元数据是**应用级**的，所以从 `EntityManagerFactory` 取一次 `Metamodel` 并缓存解析结果；
/// 不用 `EntityManager.getMetamodel()`（那是请求级对象，按它解析等于每次查询重算）。
@SuppressWarnings("PMD.TooManyMethods") // 方法数由「实体元数据查询面」决定（type/id/version/属性注解/read），拆类会把共享缓存边界拆散
public final class JpaMeta {

    private final Metamodel metamodel;
    private final Map<Class<?>, JpaEntityType<?>> cache = new ConcurrentHashMap<>();
    private final JpaEmbeddedPaths embeddedPaths;

    /// 用实体管理器工厂构建元模型。
    ///
    /// @param entityManagerFactory 实体管理器工厂，非 `null`；元模型从这里取一次
    public JpaMeta(EntityManagerFactory entityManagerFactory) {
        this.metamodel = entityManagerFactory.getMetamodel();
        this.embeddedPaths = new JpaEmbeddedPaths(metamodel);
    }

    /// 取实体元数据（首次解析后缓存）。
    ///
    /// 缓存用 `computeIfAbsent`，不用 `get` / `put` 两步：两步写法下两个线程同时首访同一实体
    /// 会各造一个实例、各自返回自己那份，"同一实体多次调用返回同一实例"在并发首访时就不成立
    /// （字段句柄也跟着分裂）。`computeIfAbsent` 把"判定 + 构造 + 落缓存"合成一次原子操作。
    ///
    /// @param type 实体类型；必须在元模型里注册，否则抛 `IllegalArgumentException`
    /// @param <T>  实体类型
    /// @return 缓存的元数据：同一实体多次调用返回同一实例
    @SuppressWarnings("unchecked")
    public <T> JpaEntityType<T> entityType(Class<T> type) {
        return (JpaEntityType<T>) cache.computeIfAbsent(type, t -> JpaEntityType.of(metamodel, t));
    }

    /// 判断「某 managed type 的某段属性是不是关联」，决定嵌套路径要 `join` 还是 `get`。
    ///
    /// 用 `managedType` 而不是 `entity`：`@Embedded` 里的下一段路径要从可嵌入类型上取属性，
    /// 它不是 entity，但嵌入属性本身 `isAssociation() == false`，因此天然不会被 join。
    /// 集合关联（`@OneToMany` / `@ManyToMany`）在 point-mall 里为零，这里按「可 join」返回 true，
    /// 不为它单独加分支——但**真正解析路径前**会先用 [#pluralAssociation] 拒掉它，见那里的说明。
    ///
    /// @param managedType 当前所在 managed type，非 `null`（可以是 `@Embeddable` 类型）
    /// @param attribute   属性名
    /// @return 关联返回 `true`；集合关联一律返回 `true`（按"可 join"处理）
    public boolean association(Class<?> managedType, String attribute) {
        Attribute<?, ?> resolved = metamodel.managedType(managedType).getAttribute(attribute);
        if (resolved instanceof PluralAttribute<?, ?, ?>) {
            return true;
        }
        return resolved.isAssociation();
    }

    /// 判断「某 managed type 的某段属性是不是集合关联」（`@OneToMany` / `@ManyToMany`）。
    ///
    /// 与 [#association] 分开是刻意的：集合关联**不能**像单值关联那样 join 进同一条查询。
    /// 一对多 join 按子行放大结果集——`list()` 出现重复实体，`count()` / `slice().total()`
    /// 数的是子行数，而 `EntityPath` 的 javadoc 承诺 `total` 是**实体行数**。正确实现要走
    /// 子查询（`exists`）或去重计数，目前没有；`compile/JpaPaths` 因此在解析到集合关联时
    /// 显式拒绝，而不是静默 join 出错数。
    ///
    /// @param managedType 当前所在 managed type，非 `null`
    /// @param attribute   属性名
    /// @return 集合关联返回 `true`
    public boolean pluralAssociation(Class<?> managedType, String attribute) {
        return metamodel.managedType(managedType).getAttribute(attribute) instanceof PluralAttribute<?, ?, ?>;
    }

    /// 判断「单值关联是否由本实体持有外键列」（`@ManyToOne` 与 owning 侧 `@OneToOne`）。
    ///
    /// 写侧（`JpaPersistExecutor` 的 `CriteriaUpdate` / 条件更新）只能写**本表**列：集合关联在
    /// 另一张表 / 连接表，`@OneToOne(mappedBy=...)` 反侧的外键列在对端表，两者都写不了也不该写
    /// （CHANGELOG 3.1.0 已披露「仍不写」）。元模型不暴露 owning 口，只能回到映射的 Java 成员上
    /// 看注解：`@OneToOne` 带非空 `mappedBy` 即反侧。
    ///
    /// @param managedType 当前所在 managed type，非 `null`
    /// @param attribute   属性名
    /// @return 单值关联且由本实体持有外键列时返回 `true`；标量 / 集合关联 / 反侧返回 `false`
    public boolean owningAssociation(Class<?> managedType, String attribute) {
        Attribute<?, ?> resolved = metamodel.managedType(managedType).getAttribute(attribute);
        if (resolved instanceof PluralAttribute<?, ?, ?> || !resolved.isAssociation()) {
            return false;
        }
        Member member = resolved.getJavaMember();
        if (!(member instanceof AnnotatedElement annotated)) {
            return true;
        }
        OneToOne oneToOne = annotated.getAnnotation(OneToOne.class);
        return oneToOne == null || oneToOne.mappedBy().isEmpty();
    }

    /// 该 managed type 上是否有名为 `attribute` 的属性。
    ///
    /// 路径解析用它在「直接命中」与「按 `@Embedded` 展开」之间分流：属性存在时行为与之前完全一样，
    /// 只有不存在时才去嵌入组件里找同名叶子。
    ///
    /// @param managedType 当前所在 managed type，非 `null`
    /// @param attribute   属性名
    /// @return 属性存在返回 `true`
    public boolean hasAttribute(Class<?> managedType, String attribute) {
        return attribute(managedType, attribute) != null;
    }

    /// 嵌入路径解析器：`select(Dto.class)` 的嵌平列靠它把 DTO 属性名回找成多段路径。
    ///
    /// 独立成类而不是塞在这里：回找是「列名 → 多段路径」的独立算法，且本类的 PMD 圈复杂度
    /// 已贴着类上限（60，见根 pom 的 PMD 配置）。
    ///
    /// @return 嵌入路径解析器，应用级共享（只读元模型，线程安全）
    public JpaEmbeddedPaths embeddedPaths() {
        return embeddedPaths;
    }

    /// 判断「某 managed type 的某段属性是不是 `@Embedded`」。
    ///
    /// 写侧要按它展开：整对象 `set(root.get(inner), value)` 会让 Hibernate 把**全部**嵌入列
    /// 展开进 `SET`（连 `@Column(updatable=false)` 的叶子一起写），所以必须先认出嵌入属性，
    /// 再逐叶子列写、逐叶子判可写。
    ///
    /// @param managedType 当前所在 managed type，非 `null`（可以是 `@Embeddable` 类型）
    /// @param attribute   属性名
    /// @return `@Embedded` 属性返回 `true`；标量 / 关联 / 不存在的属性返回 `false`
    public boolean embedded(Class<?> managedType, String attribute) {
        Attribute<?, ?> resolved = attribute(managedType, attribute);
        return resolved != null && resolved.getPersistentAttributeType() == PersistentAttributeType.EMBEDDED;
    }

    /// 标量列是否可写：`@Column(updatable = false)` 返回 `false`。
    ///
    /// 属性不存在或没有 `@Column` 时按可写处理：存在性校验留给写侧取路径的 `get(...)`，
    /// 与 `JpaEntityType.updatable` 的口径一致（那边找不到属性也返回 `true`）。
    ///
    /// @param managedType 当前所在 managed type，非 `null`（可以是 `@Embeddable` 类型）
    /// @param attribute   属性名
    /// @return 可写返回 `true`
    public boolean scalarUpdatable(Class<?> managedType, String attribute) {
        Attribute<?, ?> resolved = attribute(managedType, attribute);
        if (resolved == null) {
            return true;
        }
        Column column = annotation(resolved, Column.class);
        return column == null || column.updatable();
    }

    /// 关联外键列是否可写：`@JoinColumn(updatable = false)` 返回 `false`。
    ///
    /// 缺 `@JoinColumn` 时外键列名由 JPA 默认命名推断，按可写处理（同 `JpaEntityType.updatable`）。
    ///
    /// @param managedType 当前所在 managed type，非 `null`
    /// @param attribute   属性名
    /// @return 可写返回 `true`
    public boolean foreignKeyUpdatable(Class<?> managedType, String attribute) {
        Attribute<?, ?> resolved = attribute(managedType, attribute);
        if (resolved == null) {
            return true;
        }
        JoinColumn joinColumn = annotation(resolved, JoinColumn.class);
        return joinColumn == null || joinColumn.updatable();
    }

    /// 嵌入叶子列是否可写：叶子自身的 `@Column(updatable = false)` 与实体侧
    /// `@AttributeOverride(column = @Column(updatable = false))` 覆盖出来的只读列，任一命中即不可写。
    ///
    /// 生效映射可能来自覆盖而不是 `@Embeddable` 里的字段声明（`@Embeddable` 常常完全不带列注解），
    /// 只读判定必须看覆盖后的那一列，否则写侧会把只读列写进 `SET`、条件更新会放行。
    ///
    /// @param ownerType 持有 `@Embedded` 属性的 type，非 `null`
    /// @param attribute `@Embedded` 属性名
    /// @param path      嵌入内的叶子路径（点号分隔，如 `city` / `detail.code`），非 `null`
    /// @return 可写返回 `true`；属性不存在时按可写处理（与 `scalarUpdatable` 同口径）
    public boolean embeddedColumnUpdatable(Class<?> ownerType, String attribute, String path) {
        Column column = embeddedColumn(ownerType, attribute, path);
        return column == null || column.updatable();
    }

    /// 嵌入叶子（关联外键）列是否可写：叶子自身的 `@JoinColumn(updatable = false)` 与实体侧
    /// `@AssociationOverride(joinColumns = @JoinColumn(updatable = false))` 覆盖出来的只读外键，
    /// 任一命中即不可写。
    ///
    /// @param ownerType 持有 `@Embedded` 属性的 type，非 `null`
    /// @param attribute `@Embedded` 属性名
    /// @param path      嵌入内的叶子路径（点号分隔），非 `null`
    /// @return 可写返回 `true`；属性不存在时按可写处理（与 `foreignKeyUpdatable` 同口径）
    public boolean embeddedForeignKeyUpdatable(Class<?> ownerType, String attribute, String path) {
        JoinColumn joinColumn = embeddedJoinColumn(ownerType, attribute, path);
        return joinColumn == null || joinColumn.updatable();
    }

    /// 取嵌入叶子生效的 `@Column`：覆盖声明优先（外层属性上的点号名，或内层 `@Embedded` 属性上的
    /// 直接名），都没有才回落到叶子字段自己的注解。
    private Column embeddedColumn(Class<?> ownerType, String attribute, String path) {
        Attribute<?, ?> holder = attribute(ownerType, attribute);
        if (holder == null) {
            return null;
        }
        Column override = attributeOverride(holder, path);
        if (override != null) {
            return override;
        }
        int separator = path.indexOf('.');
        if (separator > 0) {
            String head = path.substring(0, separator);
            return embeddedColumn(holder.getJavaType(), head, path.substring(separator + 1));
        }
        Attribute<?, ?> leaf = attribute(holder.getJavaType(), path);
        return leaf == null ? null : annotation(leaf, Column.class);
    }

    /// 取嵌入叶子生效的 `@JoinColumn`：同 `embeddedColumn`，覆盖来源是 `@AssociationOverride`。
    private JoinColumn embeddedJoinColumn(Class<?> ownerType, String attribute, String path) {
        Attribute<?, ?> holder = attribute(ownerType, attribute);
        if (holder == null) {
            return null;
        }
        JoinColumn override = associationOverride(holder, path);
        if (override != null) {
            return override;
        }
        int separator = path.indexOf('.');
        if (separator > 0) {
            String head = path.substring(0, separator);
            return embeddedJoinColumn(holder.getJavaType(), head, path.substring(separator + 1));
        }
        Attribute<?, ?> leaf = attribute(holder.getJavaType(), path);
        return leaf == null ? null : annotation(leaf, JoinColumn.class);
    }

    /// 在属性声明的成员（字段 / getter）上找名字匹配的 `@AttributeOverride`，取它声明的列。
    ///
    /// 用 `getAnnotationsByType` 而不是 `getAnnotation`：容器注解（`@AttributeOverrides`）也会被它
    /// 一并展开，不用手写两套读取。
    private static Column attributeOverride(Attribute<?, ?> holder, String name) {
        for (AttributeOverride override : overrides(holder, AttributeOverride.class)) {
            if (override.name().equals(name)) {
                return override.column();
            }
        }
        return null;
    }

    /// 在属性声明的成员上找名字匹配的 `@AssociationOverride`，取它声明的外键列。
    private static JoinColumn associationOverride(Attribute<?, ?> holder, String name) {
        for (AssociationOverride override : overrides(holder, AssociationOverride.class)) {
            if (override.name().equals(name) && override.joinColumns().length > 0) {
                return override.joinColumns()[0];
            }
        }
        return null;
    }

    /// 取属性声明成员上的指定类型注解（容器注解已展开）；成员不可注解时返回空清单。
    private static <A extends Annotation> List<A> overrides(Attribute<?, ?> holder, Class<A> type) {
        Member member = holder.getJavaMember();
        if (!(member instanceof AnnotatedElement annotated)) {
            return List.of();
        }
        return List.of(annotated.getAnnotationsByType(type));
    }

    /// 取目标实体的 `@Id` 属性名。
    ///
    /// 条件更新允许「关联.`@Id` 属性名」形态的 SET 目标（写本表外键列），判定要拿目标实体的
    /// `@Id` 属性名比对路径第二段——从元模型取，不假设属性名一定是 `id`。
    ///
    /// @param entityType 实体类型，非 `null`，必须在元模型里注册
    /// @return `@Id` 属性名
    /// @throws IllegalArgumentException 该实体没有 `@Id` 属性
    public String idName(Class<?> entityType) {
        for (SingularAttribute<?, ?> singular : metamodel.entity(entityType).getSingularAttributes()) {
            if (singular.isId()) {
                return singular.getName();
            }
        }
        throw new IllegalArgumentException("实体 " + entityType.getName() + " 没有 @Id 属性");
    }

    /// 取属性声明的 Java 类型：单值关联给目标实体类型，`@Embedded` 给可嵌入类型。
    ///
    /// @param managedType 当前所在 managed type，非 `null`
    /// @param attribute   属性名
    /// @return 属性的 Java 类型
    /// @throws IllegalArgumentException 属性不存在
    public Class<?> attributeJavaType(Class<?> managedType, String attribute) {
        Attribute<?, ?> resolved = attribute(managedType, attribute);
        if (resolved == null) {
            throw new IllegalArgumentException("类型 " + managedType.getName() + " 上找不到属性 " + attribute);
        }
        return resolved.getJavaType();
    }

    /// 取某 managed type 的属性名清单（元模型声明顺序）。
    ///
    /// 写侧用它把 `@Embedded` 展开成逐叶子列的 `SET`。
    ///
    /// @param managedType 当前所在 managed type，非 `null`
    /// @return 属性名清单（不可变）
    public List<String> attributeNames(Class<?> managedType) {
        List<String> names = new ArrayList<>();
        for (Attribute<?, ?> attribute : metamodel.managedType(managedType).getAttributes()) {
            names.add(attribute.getName());
        }
        return List.copyOf(names);
    }

    /// 读取 `holder` 上某属性的值；`@Embedded` 展开逐列 `SET` 时用它取叶子值。
    ///
    /// `holder` 为 `null`（嵌入对象整体为 `null`）时返回 `null`：调用方据此把可写叶子写成
    /// `NULL`，与 Hibernate 整对象 `set` 时的展开结果一致。
    ///
    /// 按映射声明所在成员读：字段访问实体读字段，`@Access(PROPERTY)` 实体读 getter——后者字段上
    /// 没有映射声明，只认字段会让嵌入属性取值直接失败。
    ///
    /// @param managedType 当前所在 managed type，非 `null`
    /// @param attribute   属性名
    /// @param holder      属性所在对象，可为 `null`
    /// @return 属性值，可能为 `null`
    /// @throws IllegalArgumentException 属性不存在或映射成员既不是字段也不是 getter
    /// @throws IllegalStateException    反射读取失败
    public Object read(Class<?> managedType, String attribute, Object holder) {
        if (holder == null) {
            return null;
        }
        Attribute<?, ?> resolved = attribute(managedType, attribute);
        Member member = resolved == null ? null : resolved.getJavaMember();
        try {
            if (member instanceof Field field) {
                field.setAccessible(true);
                return field.get(holder);
            }
            if (member instanceof Method getter) {
                getter.setAccessible(true);
                return getter.invoke(holder);
            }
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("读取属性失败: " + managedType.getName() + "." + attribute, e);
        }
        throw new IllegalArgumentException("类型 " + managedType.getName() + " 上找不到可读字段 " + attribute);
    }

    /// 按名字取元模型属性；不存在返回 `null`。
    ///
    /// 不用 `getAttribute(...)` + catch：仓库规则禁止「catch 块吞异常返回默认值」，而
    /// 「属性不存在」在这里是有意义的结果（按可写处理），不是异常路径。
    private Attribute<?, ?> attribute(Class<?> managedType, String name) {
        for (Attribute<?, ?> attribute : metamodel.managedType(managedType).getAttributes()) {
            if (attribute.getName().equals(name)) {
                return attribute;
            }
        }
        return null;
    }

    /// 读取映射字段上的注解；成员不可注解时返回 `null`。
    private static <A extends Annotation> A annotation(Attribute<?, ?> attribute, Class<A> type) {
        Member member = attribute.getJavaMember();
        if (!(member instanceof AnnotatedElement annotated)) {
            return null;
        }
        return annotated.getAnnotation(type);
    }
}
