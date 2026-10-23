package io.github.nextentity.api.model;

/// 按位置取列的投影结果：具名元组族 [Tuple2]、[Tuple3]~[Tuple6] 都实现它。
///
/// 具名元组带静态类型，优先用 `getN()`；`get(int)` 只返回 `Object`，是给"列数在运行期才知道"的场景
/// 与映射器用的兜底入口。两列来自 `select(...)` 的双列重载，三列来自三列重载，四列及以上来自
/// [io.github.nextentity.api.ProjectionMappers] 产出的映射器（封顶六列，七列起用自定义映射器）。
///
/// `SelectStep#project(ProjectionMapper)` 的自定义映射器路径没有具名特化：无论几列，`map` 收到的都是
/// 按位置取值的通用 `Tuple`，不要强转具名元组。单列在那里也装配成长度为 1 的 `Tuple`
/// （而 `select(...)` 的单列投影直接返回列值，不产生 `Tuple`）。
public interface Tuple {

    /// 取第 `index` 列（0 基）。
    ///
    /// @param index 列下标，取值范围 `[0, size())`
    /// @return 该列的值，可能是 `null`
    Object get(int index);

    /// 列数。
    ///
    /// @return 列数，恒大于等于 1
    int size();
}
