package io.github.nextentity.core.builder;

import io.github.nextentity.api.model.Tuple;

/// `api.model.Tuple` 的实现：三列及以上投影结果的装配形态。
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
