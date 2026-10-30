package io.github.nextentity.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 接口默认实现的契约：`list(int, int)` 的默认实现是**明确拒绝**，不是能用的兜底。
///
/// 钉住这条是为了防止有人"顺手"把它实现成内存内的 skip/limit——那会把分页静默退化成全表加载，
/// 与这轮要消灭的静默近似背道而驰。
@DisplayName("Collector 默认实现")
class CollectorTest {

    @Test
    @DisplayName("list(offset, limit) 默认拒绝：自定义实现必须自己覆盖")
    void listWithWindowRejectedByDefault() {
        Collector<String> collector = new MinimalCollector();

        assertThatThrownBy(() -> collector.list(1, 2))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("未支持带窗口分页");
    }

    /// 只实现 3.0.0 就有的方法：带窗口分页走默认实现。
    private static final class MinimalCollector implements Collector<String> {

        @Override
        public List<String> list() {
            return List.of();
        }

        @Override
        public List<String> list(int limit) {
            return List.of();
        }

        @Override
        public String first() {
            return null;
        }

        @Override
        public String single() {
            return null;
        }

        @Override
        public long count() {
            return 0;
        }

        @Override
        public boolean exists() {
            return false;
        }
    }
}
