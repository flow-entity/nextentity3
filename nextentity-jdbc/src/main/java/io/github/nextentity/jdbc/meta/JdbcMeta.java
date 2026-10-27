package io.github.nextentity.jdbc.meta;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/// 实体元数据缓存：`Class` → [JdbcEntityType]，一次扫描、全程复用。
///
/// 与 `JpaMeta` 同形（`ConcurrentHashMap.computeIfAbsent`），但数据来自注解而不是 JPA 元模型。
/// 目标关联的目标表/主键列在渲染期由编译器经本类惰性解析，所以扫描期**不**递归解析关联目标
/// （双向 `@ManyToOne` 会撞 `computeIfAbsent` 的 `Recursive update`）。
public final class JdbcMeta {

    private final Map<Class<?>, JdbcEntityType<?>> cache = new ConcurrentHashMap<>();

    /// 取（或首次解析并缓存）实体元数据。
    ///
    /// @param type 实体类型，非 `null`
    /// @param <T>  实体类型
    /// @return 元数据；未映射时按 §4.3 #1–#14 抛异常
    @SuppressWarnings("unchecked") // 缓存键就是 type()，写入时与读出时泛型一致
    public <T> JdbcEntityType<T> entityType(Class<T> type) {
        Objects.requireNonNull(type, "type");
        return (JdbcEntityType<T>) cache.computeIfAbsent(type, JdbcEntityType::of);
    }
}
