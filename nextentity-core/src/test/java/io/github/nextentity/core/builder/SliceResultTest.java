package io.github.nextentity.core.builder;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.model.Slice;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `SliceResult` 的值语义与 `to()` 透传。
///
/// 守护的变异体：`SliceResult.java:21` 的 `to()` 返回 `null`
/// （`return mapper.apply(this)` → `return null`）——用例断言映射函数收到的就是自身、
/// 且返回值原样透传，因此返回 `null` 一定失败。
@DisplayName("SliceResult")
class SliceResultTest {

    @Test
    @DisplayName("to()：入参就是自身，data 与 total 保持一致")
    void toPassesItselfWithDataAndTotal() {
        SliceResult<String> slice = new SliceResult<>(List.of("a", "b"), 42L);

        Slice<String> seen = slice.to(s -> s);

        assertThat(seen).isNotNull().isSameAs(slice);
        assertThat(seen.data()).containsExactly("a", "b");
        assertThat(seen.total()).isEqualTo(42L);
    }

    @Test
    @DisplayName("to()：映射函数的返回值原样透传")
    void toReturnsMapperResult() {
        SliceResult<String> slice = new SliceResult<>(List.of("a"), 3L);

        String rendered = slice.to(s -> s.data().size() + ":" + s.total());

        assertThat(rendered).isEqualTo("1:3");
    }

    @Test
    @DisplayName("构造：data 是入参的快照")
    void constructorSnapshotsData() {
        List<String> rows = new ArrayList<>(List.of("a"));

        SliceResult<String> slice = new SliceResult<>(rows, 1L);
        rows.add("b");

        assertThat(slice.data()).containsExactly("a");
    }
}
