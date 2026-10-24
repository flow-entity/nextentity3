package io.github.nextentity.core.builder;

import io.github.nextentity.api.model.Slice;
import java.util.List;
import java.util.function.Function;

/// `api.model.Slice` 的实现：一页数据 + 总数。
///
/// 独立于后端：`slice()` 先 `count()` 再决定要不要跑列表查询（2.2.3 `Collector.slice` 的行为），
/// 因此总数与数据可能来自两条语句，但对外是一个不可变值。
///
/// @param <T> 元素类型
record SliceResult<T>(List<T> data, long total) implements Slice<T> {

    SliceResult {
        // 不能用 `List.copyOf`：投影列可以是 NULL（`select(可空列).slice(...)` 的每一行就是一个可能为
        // null 的值），`List.copyOf` 遇到 null 元素抛 NPE——整页数据直接炸，而不是返回那一行的 null。
        // 2.2.3 的 `SliceImpl` 是纯 record，原样持有调用方给的 list，允许 null；
        // `Stream.toList()` 同样不可变，但允许 null 元素。
        data = data.stream().toList();
    }

    @Override
    public <R> R to(Function<Slice<T>, R> mapper) {
        return mapper.apply(this);
    }
}
