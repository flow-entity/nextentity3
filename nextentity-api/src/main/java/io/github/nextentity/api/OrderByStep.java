package io.github.nextentity.api;

/// 排序入口。
///
/// 两个重载分别接「用户写的方法引用」与「api 侧的属性路径值」；后端**只能**从
/// `PathRef`（lambda）或 [Path#refs()] 解析属性，不得把 `Path` 当 lambda。
///
/// @param <T> 根实体类型
/// @param <U> 结果类型
public interface OrderByStep<T, U> {

    /// 按方法引用形式的属性排序。
    ///
    /// @param ref 排序属性，非 `null`；签名已限定为可比较属性
    /// @return 排序方向：必须紧跟 `asc()` / `desc()`
    OrderOperator<T, U> orderBy(PathRef<T, ? extends Comparable<?>> ref);

    /// 按属性路径（`Path.of(...)` / `EntityPath.of(...)` 的结果）排序。
    ///
    /// @param path 属性路径，非 `null`；嵌套路径会先 join 再排序
    /// @return 排序方向：必须紧跟 `asc()` / `desc()`
    OrderOperator<T, U> orderBy(Path<T, ? extends Comparable<?>> path);
}
