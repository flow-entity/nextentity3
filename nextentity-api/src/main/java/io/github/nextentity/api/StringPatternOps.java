package io.github.nextentity.api;

/// 字符串「前缀/后缀/否定」模式算子。
///
/// 这些方法都由 [like]/[notLike] 拼出，语义与 2.2.3 一致：`startsWith(v)` 等价 `like(v + "%")`，
/// `notContains(v)` 等价 `notLike("%" + v + "%")`。通配符只在这一处拼，后端只认 `LIKE`/`NOT LIKE`。
///
/// 继承 [StringOps] 只是为了默认方法里能调到 `like`，不新增对实现方的要求。
///
/// @param <B> 继续链式的步骤类型
public interface StringPatternOps<B> extends StringOps<B> {

    /// 以给定前缀开头（`prefix%`）。
    ///
    /// @param prefix 前缀，非 `null`
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    default B startsWith(String prefix) {
        return like(prefix + "%");
    }

    /// 以给定后缀结尾（`%suffix`）。
    ///
    /// @param suffix 后缀，非 `null`
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    default B endsWith(String suffix) {
        return like("%" + suffix);
    }

    /// LIKE 匹配取反，模式**原样**进 `NOT LIKE`，调用方自带 `%` / `_`。
    ///
    /// 与 [StringOps#like] 的关系同 `contains` 与 `like`：`notContains` 会替你包 `%`，本方法不会。
    ///
    /// @param pattern LIKE 模式，非 `null`
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    B notLike(String pattern);

    /// 不以给定前缀开头（`NOT LIKE prefix%`）。
    ///
    /// @param prefix 前缀，非 `null`
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    default B notStartsWith(String prefix) {
        return notLike(prefix + "%");
    }

    /// 不以给定后缀结尾（`NOT LIKE %suffix`）。
    ///
    /// @param suffix 后缀，非 `null`
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    default B notEndsWith(String suffix) {
        return notLike("%" + suffix);
    }

    /// 不包含子串（`NOT LIKE %value%`）。
    ///
    /// NULL 语义照 SQL：`NOT LIKE` 对 `null` 列求值为 `UNKNOWN`，所以本条件**不匹配**该列为 `null` 的行
    /// （要连 `null` 行一起算，写成 `or(notContains(v), isNull())`）。
    ///
    /// @param value 子串，非 `null`
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    default B notContains(String value) {
        return notLike("%" + value + "%");
    }
}
