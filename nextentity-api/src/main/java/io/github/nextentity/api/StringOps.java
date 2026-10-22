package io.github.nextentity.api;

/// 字符串算子。
///
/// @param <B> 继续链式的步骤类型
public interface StringOps<B> {

    /// 包含子串（`%value%`）。
    ///
    /// @param value 子串
    /// @return 下一步
    B contains(String value);

    /// 包含子串，值为 `null` 或空串时短路。
    ///
    /// @param value 子串
    /// @return 下一步
    B containsIfNotEmpty(String value);

    /// 包含子串，值为 `null` 时短路。
    ///
    /// @param value 子串
    /// @return 下一步
    B containsIfNotNull(String value);

    /// LIKE 匹配，值为 `null` 时短路。调用方自带通配符。
    ///
    /// @param pattern LIKE 模式
    /// @return 下一步
    B likeIfNotNull(String pattern);

    /// 等于，值为 `null` 或空串时短路。
    ///
    /// @param value 比较值
    /// @return 下一步
    B eqIfNotEmpty(String value);
}
