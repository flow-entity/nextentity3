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
        // 投影列可以是 NULL，而 `List.copyOf` 遇到 null 元素抛 NPE（2.2.3 的 `SliceImpl` 允许 null）。
        // `Stream.toList()` 同样不可变，但允许 null。
        data = data.stream().toList();
    }

    @Override
    public <R> R to(Function<Slice<T>, R> mapper) {
        return mapper.apply(this);
    }
}
