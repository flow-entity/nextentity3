package io.github.nextentity.api;

/// 等值比较算子。
///
/// @param <T> 根实体类型
/// @param <U> 属性值类型
/// @param <B> 继续链式的步骤类型
public interface Comparison<T, U, B> {

    /// 等于。
    ///
    /// @param value 比较值，非 `null`（要"为 null 就不加条件"请用 `eqIfNotNull`）
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    B eq(U value);

    /// 等于，值为 `null` 时整条条件短路（不产生谓词）。
    ///
    /// @param value 比较值；`null` 表示"不加这条条件"，不是"匹配 null 行"
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    B eqIfNotNull(U value);

    /// 不等于。
    ///
    /// @param value 比较值，非 `null`
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    B ne(U value);
}
