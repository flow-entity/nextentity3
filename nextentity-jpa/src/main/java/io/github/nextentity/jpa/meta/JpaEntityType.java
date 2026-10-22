package io.github.nextentity.jpa.meta;

import io.github.nextentity.core.meta.EntityAttribute;
import io.github.nextentity.core.meta.EntityType;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.SingularAttribute;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/// `EntityType` 的 JPA 实现：从 `EntityManagerFactory.getMetamodel()` 读出 id / version / 属性表。
///
/// 只在应用启动后解析一次（由 {@link JpaMeta} 缓存）。不判断关联：关联判定需要沿路径逐段问
/// 「当前 managed type 的这段属性是不是关联」，而 managed type 也可能是 `@Embeddable`，
/// 因此那条能力放在 {@link JpaMeta#association(Class, String)}。
///
/// @param <T> 实体类型
public final class JpaEntityType<T> implements EntityType<T> {

    private final Class<T> type;
    private final EntityAttribute id;
    private final EntityAttribute version;
    private final Map<String, EntityAttribute> attributes;

    private JpaEntityType(
            Class<T> type, EntityAttribute id, EntityAttribute version, Map<String, EntityAttribute> attributes) {
        this.type = type;
        this.id = id;
        this.version = version;
        this.attributes = Collections.unmodifiableMap(attributes);
    }

    /// 解析一个实体类型。
    ///
    /// @param metamodel JPA 元模型
    /// @param type      实体类型
    /// @param <T>       实体类型
    /// @return 实体元数据
    static <T> JpaEntityType<T> of(Metamodel metamodel, Class<T> type) {
        Map<String, EntityAttribute> attributes = new LinkedHashMap<>();
        EntityAttribute id = null;
        EntityAttribute version = null;
        for (Attribute<?, ?> attribute : metamodel.entity(type).getAttributes()) {
            EntityAttribute meta = new JpaEntityAttribute(type, attribute.getName());
            attributes.put(attribute.getName(), meta);
            if (attribute instanceof SingularAttribute<?, ?> singular) {
                if (singular.isId()) {
                    id = meta;
                } else if (singular.isVersion()) {
                    version = meta;
                }
            }
        }
        if (id == null) {
            throw new IllegalArgumentException("实体 " + type.getName() + " 没有 @Id 属性");
        }
        return new JpaEntityType<>(type, id, version, attributes);
    }

    @Override
    public Class<T> type() {
        return type;
    }

    @Override
    public EntityAttribute id() {
        return id;
    }

    @Override
    public EntityAttribute version() {
        return version;
    }

    /// 全部映射属性（含 id 与 version），按元模型声明顺序。
    ///
    /// @return 属性名到读写句柄的映射
    public Map<String, EntityAttribute> attributes() {
        return attributes;
    }
}
