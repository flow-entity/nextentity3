package io.github.nextentity.core.builder;

import io.github.nextentity.api.model.Tuple;

/// `api.model.Tuple` 的实现：按位置取列的通用装配形态。
///
/// 1 列与 3 列及以上都装配成本类；两列的 `select(...)` 投影走 [Tuple2Value]（多带具名 `get0`/`get1`，
/// 同样是 `Tuple`），而映射器路径下两列也用本类，因为映射器只按位置取值，不需要具名访问器。
///
/// 与 [Tuple2Value] 一样，后端只给原始行（`Object[]`），包成元组是 core 的责任。
/// 构造时复制数组，避免调用方后续改动影响已装配的结果。
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
