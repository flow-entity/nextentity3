package io.github.nextentity.core.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/// 不可变列表工具：把一个 `Iterable` 收成不可变 `List`。
///
/// 唯一调用点是 point-mall 的 `Entities.of(...)`（`Entities.java:43`），它把惰性来源收成
/// `List` 后缓存起来；符号名保持不变是为了不动消费方（D2 零改动）。
///
/// 返回类型声明为 `List` 而不是具体类：调用方只需要 `List`，把实现类写进签名会让替换实现变成破坏性变更。
public final class ImmutableList {

    private ImmutableList() {}

    /// 从可迭代对象创建不可变列表。
    ///
    /// 来源可能是惰性的（由 `Supplier` 现算），因此就地复制一次，之后不再触碰来源；
    /// 元素本身允许为 null（不用 `List.copyOf`，避免对调用方的空元素抛 NPE）。
    ///
    /// @param iterable 来源，非 `null`；可能是惰性的，只会被遍历一次
    /// @param <E>      元素类型
    /// @return 不可变列表：元素允许为 `null`，`add`/`set`/`remove` 抛 `UnsupportedOperationException`
    public static <E> List<E> ofIterable(Iterable<? extends E> iterable) {
        List<E> copy = iterable instanceof Collection<?> sized ? new ArrayList<>(sized.size()) : new ArrayList<>();
        for (E element : iterable) {
            copy.add(element);
        }
        return Collections.unmodifiableList(copy);
    }
}
