package io.github.nextentity.core.meta;

/// 实体属性：一个字段的读写句柄，后端无关。
///
/// 只提供"按名读写"这一件事：`id` 用于拼主键路径与提取主键值，
/// `version` 用于乐观锁版本号回写。不暴露 `Field`/`Attribute` 等后端类型。
public interface EntityAttribute {

    /// 属性名（JPA 字段名，不是列名）。
    String name();

    /// 属性 Java 类型。
    Class<?> type();

    /// 从实体上读该属性值。
    Object get(Object entity);

    /// 向实体写该属性值。
    void set(Object entity, Object value);
}
