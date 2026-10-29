package io.github.nextentity.core.util;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;

/// 不可变列表工具：仓库里把来源「收成不可变 `List`」的唯一出口。
///
/// 两个入口：[#copyOf(Collection)] 复制一个有界集合，[#ofIterable(Iterable)] 收一个可能惰性的来源
/// （point-mall 的 `Entities.of(...)` 依赖后者，符号名保持不变是为了不动消费方）。
///
/// **什么时候用它，而不是 `List.copyOf` / `Collections.unmodifiableList(new ArrayList<>(x))`**：
/// - 来源可能含 `null`（`IN` 参数、投影列）——`List.copyOf` 遇到 `null` 直接抛 NPE；
/// - 要「复制一次就固定」的快照，且不想再套一个 `Collections` 包装对象；
/// - 入参可能已经是本类的产物——[#copyOf(Collection)] 直接返回原实例，不会重复复制。
///
/// 返回类型声明为 `List` 而不是具体类：调用方只需要 `List`，把实现类写进签名会让替换实现变成破坏性变更。
public final class ImmutableList {

    private ImmutableList() {}

    /// 从可迭代对象创建不可变列表；来源只会被遍历一次，可能是惰性的（由 `Supplier` 现算）。
    ///
    /// @param iterable 来源，非 `null`
    /// @param <E>      元素类型
    /// @return 不可变列表：元素允许为 `null`，`add`/`set`/`remove` 抛 `UnsupportedOperationException`
    public static <E> List<E> ofIterable(Iterable<? extends E> iterable) {
        Objects.requireNonNull(iterable, "iterable");
        if (iterable instanceof Collection<? extends E> sized) {
            return copyOf(sized);
        }
        List<E> collected = new ArrayList<>();
        for (E element : iterable) {
            collected.add(element);
        }
        return copyOf(collected);
    }

    /// 把集合复制成不可变列表：元素允许为 `null`；入参已是本类产物时返回原实例。
    ///
    /// @param collection 来源，非 `null`
    /// @param <E>        元素类型
    /// @return 不可变列表，内容与来源一致
    @SuppressWarnings("unchecked") // 本类产物恒为不可变，按元素类型原样返回
    public static <E> List<E> copyOf(Collection<? extends E> collection) {
        Objects.requireNonNull(collection, "collection");
        if (collection instanceof ImmutableArrayList<?>) {
            return (List<E>) collection;
        }
        if (collection.isEmpty()) {
            return List.of();
        }
        return new ImmutableArrayList<>(collection.toArray());
    }

    /// 数组支撑的只读列表：`get` 只多一次下标；迭代与列表相等性由 `AbstractList` 按 `get` 实现。
    private static final class ImmutableArrayList<E> extends AbstractList<E> implements RandomAccess {

        private final Object[] elements;

        ImmutableArrayList(Object[] elements) {
            this.elements = elements;
        }

        @Override
        @SuppressWarnings("unchecked")
        public E get(int index) {
            Objects.checkIndex(index, elements.length);
            return (E) elements[index];
        }

        @Override
        public int size() {
            return elements.length;
        }
    }
}
