package io.github.nextentity.api;

/// 大小比较算子。
///
/// @param <T> 根实体类型
/// @param <U> 属性值类型
/// @param <B> 继续链式的步骤类型
public interface Range<T, U, B> {

    /// 大于等于。
    ///
    /// @param value 比较值，非 `null`（要"为 null 就不加条件"请用 `geIfNotNull`）
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    B ge(U value);

    /// 大于。
    ///
    /// @param value 比较值，非 `null`（要"为 null 就不加条件"请用 `gtIfNotNull`）
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    B gt(U value);

    /// 小于等于。
    ///
    /// @param value 比较值，非 `null`（要"为 null 就不加条件"请用 `leIfNotNull`）
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    B le(U value);

    /// 小于。
    ///
    /// @param value 比较值，非 `null`（要"为 null 就不加条件"请用 `ltIfNotNull`）
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    B lt(U value);
}
