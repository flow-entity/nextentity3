package io.github.nextentity.api;

import java.io.Serializable;

/// 方法引用形式的属性路径，例如 `Order::getId`。
///
/// 保留方法引用而不是字符串属性名，是为了让存量调用点零迁移且编译期可查。
///
/// @param <T> 声明该属性的实体类型
/// @param <R> 属性值类型
public interface PathRef<T, R> extends Serializable, Expression<T, R> {

    /// 从实体上读取该属性的值。
    ///
    /// @param entity 实体实例
    /// @return 属性值
    R apply(T entity);

    /// 字符串属性引用。
    ///
    /// @param <T> 实体类型
    interface StringRef<T> extends PathRef<T, String> {}

    /// 数值属性引用。
    ///
    /// @param <T> 实体类型
    /// @param <R> 数值类型
    interface NumberRef<T, R extends Number> extends PathRef<T, R> {}
}
