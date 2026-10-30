package io.github.nextentity.api;

/// `where(...)` 返回的字符串算子：通用算子 + 字符串算子 + 模式算子 + 字符串函数。
///
/// 全部方法都在被继承的窄接口里，本接口只做组合（架构门禁：单个接口声明的方法数 ≤ 6）。
///
/// @param <T> 根实体类型
/// @param <B> 继续链式的步骤类型
public interface StringOperator<T, B>
        extends Comparison<T, String, B>,
                Nullness<B>,
                Membership<T, String, B>,
                StringOps<B>,
                StringPatternOpsIfNotEmpty<B>,
                StringFunctions<T, B> {}
