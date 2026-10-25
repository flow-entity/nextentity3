package io.github.nextentity.jpa.meta;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.PluralAttribute;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// JPA 元模型入口：实体元数据解析 + 关联判定。
///
/// 元数据是**应用级**的，所以从 `EntityManagerFactory` 取一次 `Metamodel` 并缓存解析结果；
/// 不用 `EntityManager.getMetamodel()`（那是请求级对象，按它解析等于每次查询重算）。
public final class JpaMeta {

    private final Metamodel metamodel;
    private final Map<Class<?>, JpaEntityType<?>> cache = new ConcurrentHashMap<>();

    /// 用实体管理器工厂构建元模型。
    ///
    /// @param entityManagerFactory 实体管理器工厂，非 `null`；元模型从这里取一次
    public JpaMeta(EntityManagerFactory entityManagerFactory) {
        this.metamodel = entityManagerFactory.getMetamodel();
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
}
