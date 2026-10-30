package io.github.nextentity.api;

/// 字符串「前缀/后缀/否定」模式算子，值为 `null` 或空串时短路。
///
/// 空串按"没有给值"处理：`startsWithIfNotEmpty("")` 返回不带条件的下一步，
/// 而不是 `LIKE '%'`（后者会命中所有非 `null` 值，属于静默放宽查询范围）。
///
/// @param <B> 继续链式的步骤类型
public interface StringPatternOpsIfNotEmpty<B> extends StringPatternOpsIfNotNull<B> {

    /// 以给定前缀开头，值为 `null` 或空串时短路。
    ///
    /// @param prefix 前缀；`null` 或空串表示"不加这条条件"
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    default B startsWithIfNotEmpty(String prefix) {
        return startsWithIfNotNull(isEmpty(prefix) ? null : prefix);
    }

    /// 以给定后缀结尾，值为 `null` 或空串时短路。
    ///
    /// @param suffix 后缀；`null` 或空串表示"不加这条条件"
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    default B endsWithIfNotEmpty(String suffix) {
        return endsWithIfNotNull(isEmpty(suffix) ? null : suffix);
    }

    /// LIKE 匹配取反，值为 `null` 或空串时短路。调用方自带通配符。
    ///
    /// @param pattern LIKE 模式；`null` 或空串表示"不加这条条件"
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    default B notLikeIfNotEmpty(String pattern) {
        return notLikeIfNotNull(isEmpty(pattern) ? null : pattern);
    }

    /// 不以给定前缀开头，值为 `null` 或空串时短路。
    ///
    /// @param prefix 前缀；`null` 或空串表示"不加这条条件"
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    default B notStartsWithIfNotEmpty(String prefix) {
        return notStartsWithIfNotNull(isEmpty(prefix) ? null : prefix);
    }

    /// 不以给定后缀结尾，值为 `null` 或空串时短路。
    ///
    /// @param suffix 后缀；`null` 或空串表示"不加这条条件"
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    default B notEndsWithIfNotEmpty(String suffix) {
        return notEndsWithIfNotNull(isEmpty(suffix) ? null : suffix);
    }

    /// 不包含子串，值为 `null` 或空串时短路。
    ///
    /// @param value 子串；`null` 或空串表示"不加这条条件"
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    default B notContainsIfNotEmpty(String value) {
        return notContainsIfNotNull(isEmpty(value) ? null : value);
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
