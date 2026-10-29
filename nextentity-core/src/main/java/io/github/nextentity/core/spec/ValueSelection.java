package io.github.nextentity.core.spec;

import io.github.nextentity.core.util.ImmutableList;
import java.util.List;
import java.util.Objects;

/// 表达式查询：`SELECT 投影项...`，单项即单值结果，多项即元组结果。
///
/// 装配方式（`rowMapper`）是本记录的一部分，不是执行器的第二个参数：「查哪些列」与
/// 「查回来的列怎么变成结果对象」由同一个值拥有，因此两者不可能被错配——值查询一造出来就必有
/// 映射器，不存在「有列集却没映射器」的半成品状态（旧的 `mapper == null` 运行期拒绝已随之消失）。
///
/// 等价性只看 [items] 与 [distinct]，**不看** [rowMapper]：SQL 相同的两份规格应当相等，
/// 而装配方式不进入语句。这样既能整值断言规格（见 `SpecStructureTest`），也不会因为
/// 两个 lambda 不是同一个实例而把同一份查询判成不同。
///
/// @param items     投影项，非空
/// @param distinct  是否 `SELECT DISTINCT`
/// @param rowMapper 一行结果怎么装配成结果对象，非 `null`
public record ValueSelection(List<Projection> items, boolean distinct, RowMapper<?> rowMapper) implements Selection {

    /// 紧凑构造器：校验并复制为不可变列表。
    public ValueSelection {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(rowMapper, "rowMapper");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("表达式查询至少需要一个投影项");
        }
        items = ImmutableList.copyOf(items);
    }

    /// 列集与去重标志决定的相等性，映射器不参与。
    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof ValueSelection that && distinct == that.distinct && items.equals(that.items);
    }

    /// 与 [#equals(Object)] 对齐：只由列集与去重标志决定。
    @Override
    public int hashCode() {
        return Objects.hash(items, distinct);
    }
}
