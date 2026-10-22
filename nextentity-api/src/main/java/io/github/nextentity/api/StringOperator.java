package io.github.nextentity.api;

/// `where(...)` 返回的字符串算子：通用算子 + 字符串算子。
///
/// @param <T> 根实体类型
/// @param <B> 继续链式的步骤类型
public interface StringOperator<T, B>
        extends Comparison<T, String, B>, Nullness<B>, Membership<T, String, B>, StringOps<B> {}
