package io.github.nextentity.core.util;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.RandomAccess;

/// 不可变列表：构造后不再变化，可安全共享给调用方。
///
/// 只保留 `ofIterable` —— 唯一调用点是 point-mall 的 `Entities.of(...)`
/// （`Entities.java:43`），它把一个 `Iterable` 收成 `List` 后缓存起来。
///
/// @param <E> 元素类型
public final class ImmutableList<E> extends AbstractList<E> implements RandomAccess {

    private final List<E> elements;

    private ImmutableList(List<E> elements) {
        this.elements = elements;
    }

    /// 从可迭代对象创建不可变列表。
    ///
    /// 来源可能是惰性的（由 `Supplier` 现算），因此就地复制一次，之后不再触碰来源；
    /// 元素本身允许为 null（不做 `List.copyOf`，避免对调用方的空元素抛 NPE）。
    ///
    /// @param iterable 来源
    /// @param <E>      元素类型
    /// @return 不可变列表
    public static <E> ImmutableList<E> ofIterable(Iterable<? extends E> iterable) {
        ArrayList<E> copy = new ArrayList<>();
        if (iterable instanceof Collection<?> sized) {
            copy.ensureCapacity(sized.size());
        }
        for (E element : iterable) {
            copy.add(element);
        }
        return new ImmutableList<>(Collections.unmodifiableList(copy));
    }

    @Override
    public E get(int index) {
        return elements.get(index);
    }

    @Override
    public int size() {
        return elements.size();
    }
}
