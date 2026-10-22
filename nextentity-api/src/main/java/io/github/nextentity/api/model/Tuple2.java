package io.github.nextentity.api.model;

/// 两列投影结果。
///
/// @param <A> 第一列类型
/// @param <B> 第二列类型
public interface Tuple2<A, B> {

    /// 第一列。
    ///
    /// @return 第一列的值，即 `select(first, second)` 的 `first`
    A get0();

    /// 第二列。
    ///
    /// @return 第二列的值，即 `select(first, second)` 的 `second`
    B get1();
}
