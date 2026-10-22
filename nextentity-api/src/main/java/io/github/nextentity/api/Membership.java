package io.github.nextentity.api;

import java.util.Collection;

/// 集合成员算子。
///
/// @param <T> 根实体类型
/// @param <U> 属性值类型
/// @param <B> 继续链式的步骤类型
public interface Membership<T, U, B> {

    /// 在给定集合内。
    ///
    /// @param values 候选集合，非 `null`；空集合匹配不到任何行（恒假）
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    B in(Collection<? extends U> values);

    /// 在给定取值内。
    ///
    /// @param values 候选取值；空变长等同于空集合（恒假），元素允许为 `null`
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    B in(U... values);

    /// 不在给定集合内。
    ///
    /// @param values 排除集合，非 `null`；空集合匹配所有行（恒真）
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    B notIn(Collection<? extends U> values);

    /// 不在给定取值内。
    ///
    /// @param values 排除取值；空变长等同于空集合（恒真），元素允许为 `null`
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    B notIn(U... values);

    /// 在给定集合内，集合为 `null` 时短路。
    ///
    /// @param values 候选集合；`null` 表示"不加这条条件"
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    B inIfNotNull(Collection<? extends U> values);
}
