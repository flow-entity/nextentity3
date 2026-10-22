package io.github.nextentity.jpa.meta;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;
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
    /// @param entityManagerFactory 实体管理器工厂
    public JpaMeta(EntityManagerFactory entityManagerFactory) {
        this.metamodel = entityManagerFactory.getMetamodel();
    }

    /// 取实体元数据（首次解析后缓存）。
    ///
    /// @param type 实体类型
    /// @param <T>  实体类型
    /// @return 实体元数据
    @SuppressWarnings("unchecked")
    public <T> JpaEntityType<T> entityType(Class<T> type) {
        JpaEntityType<?> cached = cache.get(type);
        if (cached == null) {
            cached = JpaEntityType.of(metamodel, type);
            cache.put(type, cached);
        }
        return (JpaEntityType<T>) cached;
    }

    /// 判断「某 managed type 的某段属性是不是关联」，决定嵌套路径要 `join` 还是 `get`。
    ///
    /// 用 `managedType` 而不是 `entity`：`@Embedded` 里的下一段路径要从可嵌入类型上取属性，
    /// 它不是 entity，但嵌入属性本身 `isAssociation() == false`，因此天然不会被 join。
    /// 集合关联（`@OneToMany` / `@ManyToMany`）在 point-mall 里为零，这里按「可 join」返回 true，
    /// 不为它单独加分支。
    ///
    /// @param managedType 当前所在 managed type
    /// @param attribute   属性名
    /// @return 关联返回 true
    public boolean association(Class<?> managedType, String attribute) {
        Attribute<?, ?> resolved = metamodel.managedType(managedType).getAttribute(attribute);
        if (resolved instanceof PluralAttribute<?, ?, ?>) {
            return true;
        }
        return ((SingularAttribute<?, ?>) resolved).isAssociation();
    }
}
