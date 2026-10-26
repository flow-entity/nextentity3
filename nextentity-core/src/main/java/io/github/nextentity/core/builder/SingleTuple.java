package io.github.nextentity.core.builder;

import io.github.nextentity.api.model.Tuple;

/// 单列投影的 `Tuple`：直接持有那一个值，不借道数组。
///
/// [RowAssembler] 的单列分支要把那一列交给 `ValueSelection#rowMapper()`，而映射器只收 `Tuple`，
/// 因此需要一层「长度恒为 1」的包装。本类把这个长度写进类型，`get`/`size` 都不必再碰数组。
///
/// 只在 [RowAssembler] 的逐行装配里出现，寿命不超过一次 `map` 调用。
///
/// @param value 唯一那一列的值，可为 `null`
record SingleTuple(Object value) implements Tuple {

    @Override
    public Object get(int index) {
        if (index != 0) {
            throw new IndexOutOfBoundsException("单列元组没有下标 " + index);
        }
        return value;
    }

    @Override
    public int size() {
        return 1;
    }
}
