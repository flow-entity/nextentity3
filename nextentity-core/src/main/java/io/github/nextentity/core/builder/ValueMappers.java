package io.github.nextentity.core.builder;

import io.github.nextentity.api.model.Tuple2;
import io.github.nextentity.api.model.Tuple3;
import io.github.nextentity.core.spec.RowMapper;

/// `select(...)` 的内置映射器：把 1/2/3 列投影包成与 `project(...)` 同一条装配路径。
///
/// 为什么 `select(...)` 也要有映射器：`SelectStep#select` 的返回类型是具名元组（`Tuple2`/`Tuple3`），
/// 而装配如果按「列数」在 [RowAssembler] 里另开一条分支，就会出现两套并行的机器——列集在
/// `ValueSelection` 里、装配规则在 `switch (columns)` 里，新增形态必须同时改两处。
/// 本类把「几列 → 造什么对象」收进映射器，`RowAssembler` 只剩「包 `Tuple` 再 `map`」一条路。
///
/// 产出的是 [RowMapper] 而不是 `api.ProjectionMapper`：列集由 `select(...)` 的实参给出，
/// 内置映射器只描述「怎么装配」，因此不需要 `expressions()` 这种没人调用的空壳。
///
/// 本类是 `select(...)` 元组结果的**唯一**装配点：全仓只有 [QueryBuilder] 的开头三个 `select`
/// 重载引到它（`api.ProjectionMappers` 自建脚本，不经过这里）。
/// 列序与类型对位靠 `map` 里的下标字面量，与 api 侧 `ProjectionMappers` 同一套约定。
final class ValueMappers {

    private ValueMappers() {}

    /// 单列投影：结果就是列值本身（不产生 `Tuple`）。
    ///
    /// 行形态兼容（裸值 vs 长度 1 的 `Object[]`）不在这里做——那是「后端怎么渲染一行」的问题，
    /// 由 [RowAssembler#tuple] 统一整形；映射器只负责「从长度 1 的元组里取第一列」。
    ///
    /// 返回 `RowMapper` 而不是 `Function`：单列选段要能装进 [ValueSelection]（那里收 `RowMapper`），
    /// 手写单列规格就是这样用的；`RowAssembler` 已把单列行收成「1 个 cell」，所以这里只管取值。
    static RowMapper<Object> single() {
        return tuple -> tuple.get(0);
    }

    /// 两列投影：结果 [Tuple2]，列序即元组列序。
    static RowMapper<Tuple2<Object, Object>> pair() {
        return row -> new Tuple2<>(row.get(0), row.get(1));
    }

    /// 三列投影：结果 [Tuple3]，列序即元组列序。
    static RowMapper<Tuple3<Object, Object, Object>> triple() {
        return row -> new Tuple3<>(row.get(0), row.get(1), row.get(2));
    }
}
