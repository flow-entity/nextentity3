package io.github.nextentity.api;

/// 空值判断算子。
///
/// @param <B> 继续链式的步骤类型
public interface Nullness<B> {

    /// 为 `null`。
    ///
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    B isNull();

    /// 不为 `null`。
    ///
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    B isNotNull();
}
