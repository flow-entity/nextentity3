package io.github.nextentity.core.builder;

import io.github.nextentity.api.model.Tuple;

/// `api.model.Tuple` 的实现：按位置取列的通用装配形态。
///
/// 两列及以上**且由装配层造出来**的元组一律是本类：`map(Tuple)` 按位置取值，不需要具名访问器。
/// `select(...)` 的**结果**仍分别是裸值 / [io.github.nextentity.api.model.Tuple2] /
/// [io.github.nextentity.api.model.Tuple3]——那三个对象由 [ValueMappers] 的映射器在 `map` 里造，
/// 与交给映射器的行是不是本类无关。单列包装见 [SingleTuple]。
///
/// **不复制底层数组**：本类只在 [RowAssembler] 的逐行装配里出现，寿命不超过一次 `map` 调用，
/// 数组的所有权当场转移给本类；调用方是包内代码，构造后不会再持有或改写该数组。
///
/// 因此调用方**不得**事后修改传入的数组（包内约定，构造器不设防御）。
///
/// @param columns 列值数组，所有权转移给本类；长度已由 [RowAssembler#tuple] 对过投影列数
public record TupleValue(Object[] columns) implements Tuple {

    @Override
    public Object get(int index) {
        return columns[index];
    }

    @Override
    public int size() {
        return columns.length;
    }
}
