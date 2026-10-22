package io.github.nextentity.api;

/// 数值属性路径：比通用路径多一个求和算子。
///
/// `sum()` 与其它聚合一样返回 {@link Expression}：聚合结果不能再接聚合。
///
/// @param <T> 根实体类型
/// @param <U> 数值类型
public interface NumberPath<T, U extends Number> extends Path<T, U> {

    /// 求和。
    ///
    /// @return 求和投影
    Expression<T, U> sum();
}
