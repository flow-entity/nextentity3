package io.github.nextentity.api;

/// 列对列比较：右侧是表达式而不是字面量（`a = b`、`a > b` …）。
///
/// 单独成接口而不是塞进 [Comparison] / [Range]：那两个接口分别已有 3、4 个方法，
/// 合起来会越过「一个接口声明的公开方法 ≤ 6」的架构门禁。
///
/// 注意：左右两侧的**比较语义**由数据库决定（字符串大小写敏感性、排序规则因方言而异），
/// 一致性用例不要依赖跨方言的字符串列比较结果。
///
/// @param <T> 根实体类型
/// @param <U> 属性值类型
/// @param <B> 继续链式的步骤类型
public interface ColumnComparison<T, U, B> {

    /// 等于另一列/表达式。
    ///
    /// @param other 另一侧表达式，非 `null`
    /// @return 同一条查询的下一步
    B eq(Expression<T, U> other);

    /// 不等于另一列/表达式。
    ///
    /// @param other 另一侧表达式，非 `null`
    /// @return 同一条查询的下一步
    B ne(Expression<T, U> other);

    /// 大于等于另一列/表达式。
    ///
    /// @param other 另一侧表达式，非 `null`
    /// @return 同一条查询的下一步
    B ge(Expression<T, U> other);

    /// 大于另一列/表达式。
    ///
    /// @param other 另一侧表达式，非 `null`
    /// @return 同一条查询的下一步
    B gt(Expression<T, U> other);

    /// 小于等于另一列/表达式。
    ///
    /// @param other 另一侧表达式，非 `null`
    /// @return 同一条查询的下一步
    B le(Expression<T, U> other);

    /// 小于另一列/表达式。
    ///
    /// @param other 另一侧表达式，非 `null`
    /// @return 同一条查询的下一步
    B lt(Expression<T, U> other);
}
