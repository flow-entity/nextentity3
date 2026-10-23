package io.github.nextentity.core.builder;

import io.github.nextentity.api.model.Tuple;

/// `api.model.Tuple` 的实现：按位置取列的通用装配形态。
///
/// 只用于**映射器路径**：`map(Tuple)` 按位置取值，不需要具名访问器，所以 1 列与任意列数都包成本类。
/// `select(...)` 的 1/2/3 列投影不走这里——分别返回裸值、[io.github.nextentity.api.model.Tuple2]、
/// [io.github.nextentity.api.model.Tuple3]（具名且带类型参数）。
///
/// 后端只给原始行（`Object[]`），包成元组是 core 的责任。构造时复制数组，
/// 避免调用方后续改动影响已装配的结果。
final class TupleValue implements Tuple {

    private final Object[] columns;

    TupleValue(Object[] columns) {
        this.columns = columns.clone();
    }

    @Override
    public Object get(int index) {
        return columns[index];
    }

    @Override
    public int size() {
        return columns.length;
    }
}
