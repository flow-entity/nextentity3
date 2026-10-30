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
        SliceResult<String> slice = new SliceResult<>(List.of("a", "b"), 42L, 0, 2);

        Slice<String> seen = slice.to(s -> s);

        assertThat(seen).isNotNull().isSameAs(slice);
        assertThat(seen.data()).containsExactly("a", "b");
        assertThat(seen.total()).isEqualTo(42L);
    }

    @Test
    @DisplayName("to()：映射函数的返回值原样透传")
    void toReturnsMapperResult() {
        SliceResult<String> slice = new SliceResult<>(List.of("a"), 3L, 0, 1);

        String rendered = slice.to(s -> s.data().size() + ":" + s.total());

        assertThat(rendered).isEqualTo("1:3");
    }

    @Test
    @DisplayName("构造：data 是入参的快照")
    void constructorSnapshotsData() {
        List<String> rows = new ArrayList<>(List.of("a"));

        SliceResult<String> slice = new SliceResult<>(rows, 1L, 0, 1);
        rows.add("b");

        assertThat(slice.data()).containsExactly("a");
    }

    /// `offset()`/`limit()` 存的是**请求原值**：短路路径（`data` 为空）下也必须回报入参，
    /// 按 `data.size()` 反推会把 `limit` 报成 0，调用方的翻页参数随即失真。
    @Test
    @DisplayName("offset()/limit()：回报构造时的请求原值，而不是 data.size()")
    void reportsRequestedWindow() {
        SliceResult<String> slice = new SliceResult<>(List.of(), 3L, 10, 5);

        assertThat(slice.offset()).isEqualTo(10);
        assertThat(slice.limit()).isEqualTo(5);
    }

    /// 投影列可以是 NULL：`select(可空列).slice(...)` 的每一行就是一个可能为 null 的值，
    /// 而 `List.copyOf` 遇到 null 元素直接抛 NPE（原因见 `SliceResult` 的构造器注释）。
    @Test
    @DisplayName("构造：data 允许 null 元素（投影列可空）")
    void constructorAcceptsNullElements() {
        List<String> rows = new ArrayList<>();
        rows.add(null);
        rows.add("a");

        SliceResult<String> slice = new SliceResult<>(rows, 2L, 0, 2);

        assertThat(slice.data()).containsExactly(null, "a");
        assertThat(slice.data()).hasSize(2);
    }
}
