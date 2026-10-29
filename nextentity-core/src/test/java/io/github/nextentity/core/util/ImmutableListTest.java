package io.github.nextentity.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `ImmutableList` 的两个入口：`ofIterable`（point-mall `Entities.of(...)` 依赖它收惰性来源）
/// 与 `copyOf`（core / jdbc 的规则节点、语句对象用它做不可变快照）。
@DisplayName("ImmutableList")
class ImmutableListTest {

    @Test
    @DisplayName("ofIterable：复制集合内容，来源后续变动不影响结果")
    void copiesCollection() {
        List<String> source = new ArrayList<>(List.of("a", "b"));
        List<String> list = ImmutableList.ofIterable(source);
        source.add("c");

        assertThat(list).containsExactly("a", "b");
        assertThat(list.size()).isEqualTo(2);
        assertThat(list.get(1)).isEqualTo("b");
    }

    @Test
    @DisplayName("ofIterable：非集合的可迭代对象（惰性来源）同样能收成列表")
    void copiesLazyIterable() {
        List<Integer> list = ImmutableList.ofIterable(() -> Stream.of(1, 2, 3).iterator());

        assertThat(list).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("ofIterable：空来源得到空列表")
    void emptySource() {
        assertThat(ImmutableList.ofIterable(List.of())).isEmpty();
    }

    @Test
    @DisplayName("ofIterable：结果不可修改")
    void resultIsUnmodifiable() {
        List<String> list = ImmutableList.ofIterable(List.of("a"));

        assertThatThrownBy(() -> list.add("b")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> list.set(0, "b")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> list.removeFirst()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("copyOf：复制内容，来源后续变动不影响结果")
    void copyOfSnapshotsCollection() {
        List<String> source = new ArrayList<>(List.of("a", "b"));
        List<String> list = ImmutableList.copyOf(source);
        source.add("c");

        assertThat(list).containsExactly("a", "b");
    }

    @Test
    @DisplayName("copyOf：元素允许 null（IN 参数、投影列）")
    void copyOfAcceptsNullElements() {
        List<String> list = ImmutableList.copyOf(Arrays.asList(null, "a"));

        assertThat(list).containsExactly(null, "a");
        assertThat(list).hasSize(2);
    }

    @Test
    @DisplayName("copyOf：入参已是本类产物时返回原实例（幂等，不重复复制）")
    void copyOfIsIdempotent() {
        List<Integer> once = ImmutableList.copyOf(List.of(1, 2, 3));

        assertThat(ImmutableList.copyOf(once)).isSameAs(once);
    }

    @Test
    @DisplayName("copyOf：空来源得到空列表")
    void copyOfEmpty() {
        assertThat(ImmutableList.copyOf(List.of())).isEmpty();
        assertThat(ImmutableList.copyOf(new ArrayList<>())).isEmpty();
    }

    @Test
    @DisplayName("copyOf：结果不可修改，且与普通 List 相等")
    void copyOfResultIsUnmodifiableAndEqualsList() {
        List<String> list = ImmutableList.copyOf(List.of("a", "b"));

        assertThatThrownBy(() -> list.add("c")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> list.set(0, "c")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> list.remove(0)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(list).isEqualTo(List.of("a", "b"));
        assertThat(list.hashCode()).isEqualTo(List.of("a", "b").hashCode());
    }

    @Test
    @DisplayName("copyOf：来源为 null 时拒绝")
    void copyOfRejectsNullSource() {
        assertThatThrownBy(() -> ImmutableList.copyOf(null)).isInstanceOf(NullPointerException.class);
    }
}
