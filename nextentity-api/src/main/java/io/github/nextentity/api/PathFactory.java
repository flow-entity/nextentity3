package io.github.nextentity.api;

/// `Path.of(...)` / `EntityPath.of(...)` 的实现提供者（SPI）。
///
/// `api` 不允许依赖任何后端模块，静态工厂因此通过 `java.util.ServiceLoader` 找到唯一实现：
/// 后端模块在 `META-INF/services/io.github.nextentity.api.PathFactory` 里登记实现类即可。
public interface PathFactory {

    /// 构造通用属性路径。
    ///
    /// @param ref 方法引用形式的属性
    /// @param <T> 根实体类型
    /// @param <U> 属性值类型
    /// @return 属性路径
    <T, U> Path<T, U> path(PathRef<T, U> ref);

    /// 构造数值属性路径。
    ///
    /// @param ref 方法引用形式的数值属性
    /// @param <T> 根实体类型
    /// @param <U> 数值类型
    /// @return 数值属性路径
    <T, U extends Number> NumberPath<T, U> number(PathRef.NumberRef<T, U> ref);

    /// 构造关联属性路径。
    ///
    /// @param ref 方法引用形式的关联属性
    /// @param <T> 根实体类型
    /// @param <U> 关联实体类型
    /// @return 关联属性路径
    <T, U> EntityPath<T, U> entity(PathRef<T, U> ref);
}
