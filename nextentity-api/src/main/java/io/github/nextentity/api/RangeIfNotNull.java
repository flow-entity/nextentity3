package io.github.nextentity.api;

/// 大小比较算子，值为 `null` 时短路。
///
/// @param <T> 根实体类型
/// @param <U> 属性值类型
/// @param <B> 继续链式的步骤类型
public interface RangeIfNotNull<T, U, B> {

    /// 大于等于，值为 `null` 时短路。
    ///
    /// @param value 比较值；`null` 表示"不加这条条件"，不是"匹配 null 行"
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    B geIfNotNull(U value);

    /// 大于，值为 `null` 时短路。
    ///
    /// @param value 比较值；`null` 表示"不加这条条件"
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    B gtIfNotNull(U value);

    /// 小于等于，值为 `null` 时短路。
    ///
    /// @param value 比较值；`null` 表示"不加这条条件"
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    B leIfNotNull(U value);

    /// 小于，值为 `null` 时短路。
    ///
    /// @param value 比较值；`null` 表示"不加这条条件"
    /// @return 同一条查询的下一步：条件累加在同一构建器上
    B ltIfNotNull(U value);
}
