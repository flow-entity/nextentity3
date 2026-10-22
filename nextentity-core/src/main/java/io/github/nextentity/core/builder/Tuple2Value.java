package io.github.nextentity.core.builder;

import io.github.nextentity.api.model.Tuple2;

/// `api.model.Tuple2` 的实现：两列投影结果的装配形态。
///
/// 后端执行器对多项投影只返回原始行（`Object[]`），把它包成 `Tuple2` 是 core 的装配责任
/// （DESIGN §3「exec：结果装配」），这样第二后端不必各自实现一份。
///
/// @param <A> 第一列类型
/// @param <B> 第二列类型
/// @param get0 第一列的值：`select(first, second)` 里 `first` 的结果
/// @param get1 第二列的值：`select(first, second)` 里 `second` 的结果
record Tuple2Value<A, B>(A get0, B get1) implements Tuple2<A, B> {}
