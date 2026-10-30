package io.github.nextentity.api;

/// 数字算子：以数字表达式（如字符串长度）为主语继续比较。
///
/// 只提供比较、区间与空值短路之外的三族运算——`in` / `isNull` 的语义针对**属性**，
/// 对函数结果没有确定的 SQL 对应物，因此不在这里提供。
///
/// @param <T> 根实体类型
/// @param <B> 继续链式的步骤类型
public interface NumberOperator<T, B>
        extends Comparison<T, Integer, B>,
                Range<T, Integer, B>,
                RangeIfNotNull<T, Integer, B>,
                Between<T, Integer, B> {}
