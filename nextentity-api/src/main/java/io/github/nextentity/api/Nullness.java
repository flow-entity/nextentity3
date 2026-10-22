package io.github.nextentity.api;

/// 空值判断算子。
///
/// @param <B> 继续链式的步骤类型
public interface Nullness<B> {

    /// 为 `null`。
    ///
    /// @return 下一步
    B isNull();

    /// 不为 `null`。
    ///
    /// @return 下一步
    B isNotNull();
}
