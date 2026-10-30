package io.github.nextentity.core.builder;

import io.github.nextentity.api.model.Slice;
import io.github.nextentity.core.util.ImmutableList;
import java.util.List;
import java.util.function.Function;

/// `api.model.Slice` 的实现：一页数据 + 总数 + 本页请求的 `offset`/`limit`。
///
/// 独立于后端：`slice()` 先 `count()` 再决定要不要跑列表查询（2.2.3 `Collector.slice` 的行为），
/// 因此总数与数据可能来自两条语句，但对外是一个不可变值。
///
/// `offset`/`limit` 存的是**调用方请求的原值**，不是 `data.size()`：`limit` 为 `0` 表示不限行数
/// （`slice(0, 0)` 取回的是全部行），此时按 `data.size()` 回报页大小与请求语义相反。
///
/// @param <T>    元素类型
/// @param offset 本页请求的偏移，0 基
/// @param limit  本页请求的页大小，`0` 表示不限
record SliceResult<T>(List<T> data, long total, int offset, int limit) implements Slice<T> {

    SliceResult {
        // 投影列可以是 NULL（2.2.3 的 `SliceImpl` 允许 null），而 `List.copyOf` 遇到 null 抛 NPE。
        // `ImmutableList.copyOf` 允许 null，且比 `Stream.toList()` 少一层 stream 机制。
        data = ImmutableList.copyOf(data);
    }

    @Override
    public <R> R to(Function<Slice<T>, R> mapper) {
        return mapper.apply(this);
    }
}
