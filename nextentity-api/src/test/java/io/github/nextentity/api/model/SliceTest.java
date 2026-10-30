package io.github.nextentity.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `Slice#offset()`/`limit()` 的 **default 语义**：没实现这两个方法的实现方按「整页就是全部」
/// 回报（offset = 0，limit = data.size()），调用方因此不会拿到 `0` 之类与请求相反的页大小。
///
/// 实现方（`core` 的 `SliceResult`）能回报真实请求窗口的证据在
/// `core/builder/SliceResultTest` 与 `core/builder/QueryBuilderTest`，本套件只钉 api 自己的默认值。
@DisplayName("Slice：offset()/limit() 的默认实现")
class SliceTest {

    /// 只实现 3.0.0 那三个方法的「老实现方」：加这两个 default 方法的全部意义就是它不用改。
    private record LegacySlice<T>(List<T> data, long total) implements Slice<T> {
        @Override
        public <R> R to(Function<Slice<T>, R> mapper) {
            return mapper.apply(this);
        }
    }

    @Test
    @DisplayName("未覆盖时 offset() 返回 0（整页从第 0 行开始）")
    void offsetDefaultsToZero() {
        assertThat(new LegacySlice<>(List.of("a", "b"), 9L).offset()).isZero();
    }

    /// 默认 `limit()` 必须等于 `data.size()`：投影列可以为 `null`，但页大小仍按行数算。
    @Test
    @DisplayName("未覆盖时 limit() 返回 data().size()")
    void limitDefaultsToDataSize() {
        assertThat(new LegacySlice<>(Arrays.asList("a", "b", null), 9L).limit()).isEqualTo(3);
        assertThat(new LegacySlice<>(List.of(), 0L).limit()).isZero();
    }
}
