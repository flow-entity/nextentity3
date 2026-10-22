package io.github.nextentity.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `ImmutableList.ofIterable` 的行为：point-mall `Entities.of(...)` 依赖它把惰性来源收成不可变列表。
@DisplayName("ImmutableList")
class ImmutableListTest {

    @Test
    @DisplayName("复制集合内容，来源后续变动不影响结果")
    void copiesCollection() {
        java.util.List<String> source = new java.util.ArrayList<>(List.of("a", "b"));
        ImmutableList<String> list = ImmutableList.ofIterable(source);
        source.add("c");

        assertThat(list).containsExactly("a", "b");
        assertThat(list.size()).isEqualTo(2);
        assertThat(list.get(1)).isEqualTo("b");
    }

    @Test
    @DisplayName("非集合的可迭代对象（惰性来源）同样能收成列表")
    void copiesLazyIterable() {
        ImmutableList<Integer> list =
                ImmutableList.ofIterable(() -> Stream.of(1, 2, 3).iterator());

        assertThat(list).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("空来源得到空列表")
    void emptySource() {
        assertThat(ImmutableList.ofIterable(List.of())).isEmpty();
    }

    @Test
    @DisplayName("结果不可修改")
    void resultIsUnmodifiable() {
        ImmutableList<String> list = ImmutableList.ofIterable(List.of("a"));

        assertThatThrownBy(() -> list.add("b")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> list.set(0, "b")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> list.remove(0)).isInstanceOf(UnsupportedOperationException.class);
    }
}
