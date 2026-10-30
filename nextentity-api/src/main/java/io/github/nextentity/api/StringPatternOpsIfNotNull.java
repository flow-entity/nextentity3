package io.github.nextentity.api;

/// 字符串「前缀/后缀/否定」模式算子，值为 `null` 时短路。
///
/// 短路语义与 [StringOps#likeIfNotNull] 一致：参数为 `null` 表示"不加这条条件"（谓词恒真），
/// **不是**"匹配 `null` 行"——要匹配 `null` 行请显式写 `isNull()`。
///
/// @param <B> 继续链式的步骤类型
public interface StringPatternOpsIfNotNull<B> extends StringPatternOps<B> {

    /// 以给定前缀开头，值为 `null` 时短路。
    ///
    /// @param prefix 前缀；`null` 表示"不加这条条件"
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    default B startsWithIfNotNull(String prefix) {
        return prefix == null ? likeIfNotNull(null) : startsWith(prefix);
    }

    /// 以给定后缀结尾，值为 `null` 时短路。
    ///
    /// @param suffix 后缀；`null` 表示"不加这条条件"
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    default B endsWithIfNotNull(String suffix) {
        return suffix == null ? likeIfNotNull(null) : endsWith(suffix);
    }

    /// LIKE 匹配取反，值为 `null` 时短路。调用方自带通配符。
    ///
    /// @param pattern LIKE 模式，自带 `%`/`_`；本方法不拼 `%`。`null` 表示"不加这条条件"
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    B notLikeIfNotNull(String pattern);

    /// 不以给定前缀开头，值为 `null` 时短路。
    ///
    /// @param prefix 前缀；`null` 表示"不加这条条件"
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    default B notStartsWithIfNotNull(String prefix) {
        return prefix == null ? notLikeIfNotNull(null) : notStartsWith(prefix);
    }

    /// 不以给定后缀结尾，值为 `null` 时短路。
    ///
    /// @param suffix 后缀；`null` 表示"不加这条条件"
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    default B notEndsWithIfNotNull(String suffix) {
        return suffix == null ? notLikeIfNotNull(null) : notEndsWith(suffix);
    }

    /// 不包含子串，值为 `null` 时短路。
    ///
    /// @param value 子串；`null` 表示"不加这条条件"
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    default B notContainsIfNotNull(String value) {
        return value == null ? notLikeIfNotNull(null) : notContains(value);
    }
}
