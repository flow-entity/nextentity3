package io.github.nextentity.core.spec;

import io.github.nextentity.api.model.Tuple;

/// 值查询的一行怎么装配成结果对象：`Tuple -> R`。
///
/// 这是 [ValueSelection] 的一部分，不是执行器的参数——「查哪些列」与「查回来的列怎么变成结果」
/// 必须由同一个值持有，否则两者只能靠调用点自觉配对。
///
/// 为什么不用 `api.ProjectionMapper`：本接口要被 [QuerySpec] 间接持有，而 `spec` 包是纯值层，
/// 不依赖 api SPI 的语义（见 [QuerySpec] 类注释）。`project(...)` 的调用方映射器在
/// `core.builder.QueryBuilder` 里适配成 `mapper::map`，`andThen` 在那里就已经折叠完。
///
/// 契约：
/// - 实现必须**无状态**：[#map(Tuple)] 不得依赖上一次调用留下的状态，因为同一个 [QuerySpec]
///   可能被反复执行（`slice()` 会先 `count()` 再 `list()`）；
/// - [#map(Tuple)] 收到的行列数等于 [ValueSelection#items()] 的大小（装配层对不上就直接拒绝，
///   不会把尺寸不对的行交进来），单列投影同样是长度 1 的元组；
/// - 不参与 [ValueSelection#equals]：是否为同一份查询只看列集与 `distinct`，不看装配方式
///   （见 [ValueSelection] 的等价性说明）。
///
/// @param <R> 结果类型
@FunctionalInterface
public interface RowMapper<R> {

    /// 把一行投影结果装配成结果对象。
    ///
    /// @param row 按投影项顺序装配的行，非 `null`
    /// @return 装配结果，可为 `null`
    R map(Tuple row);
}
