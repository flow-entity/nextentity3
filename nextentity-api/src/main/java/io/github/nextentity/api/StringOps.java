package io.github.nextentity.api;

/// 字符串算子。
///
/// @param <B> 继续链式的步骤类型
public interface StringOps<B> {

    /// 包含子串（`%value%`）。
    ///
    /// @param value 子串，非 `null`；空串会命中所有非 `null` 值（要跳过空串请用 `containsIfNotEmpty`）
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    B contains(String value);

    /// 包含子串，值为 `null` 或空串时短路。
    ///
    /// @param value 子串；`null` 或空串表示"不加这条条件"
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    B containsIfNotEmpty(String value);

    /// 包含子串，值为 `null` 时短路。
    ///
    /// @param value 子串；`null` 表示"不加这条条件"（空串仍会命中所有值）
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    B containsIfNotNull(String value);

    /// LIKE 匹配，模式**原样**进 `LIKE`，调用方自带 `%` / `_`。
    ///
    /// 与 [contains] 的区别：`contains` 会替你包 `%`，本方法不会。
    ///
    /// @param pattern LIKE 模式，非 `null`
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    B like(String pattern);

    /// LIKE 匹配，值为 `null` 时短路。调用方自带通配符。
    ///
    /// @param pattern LIKE 模式，自带 `%`/`_`；本方法不拼 `%`。`null` 表示"不加这条条件"
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    B likeIfNotNull(String pattern);

    /// 等于，值为 `null` 或空串时短路。
    ///
    /// @param value 比较值；`null` 或空串表示"不加这条条件"
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    B eqIfNotEmpty(String value);
}
