package io.github.nextentity.jpa.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `@Version` 递增的唯一实现点（`JpaPersistExecutor.nextVersion`）。
///
/// 四条用例各自钉一个分支：`null` 起步为 0、`Integer` 加一、`Long` 加一、其它类型拒绝并点名
/// 类型。不需要 Hibernate——这个方法是纯函数。
class JpaPersistExecutorNextVersionTest {

    @Test
    @DisplayName("版本列为 null（新实体）：从 0 起算，返回 Integer 而不是 null")
    void nullVersionStartsAtZero() {
        assertThat(JpaPersistExecutor.nextVersion(null)).isEqualTo(0);
    }

    @Test
    @DisplayName("Integer 版本：加一，且走 Integer 分支（不是从 0 重新起算）")
    void integerVersionIncrements() {
        assertThat(JpaPersistExecutor.nextVersion(41)).isEqualTo(42);
    }

    @Test
    @DisplayName("Long 版本：加一，且走 Long 分支（不丢精度、不从 0 重新起算）")
    void longVersionIncrements() {
        assertThat(JpaPersistExecutor.nextVersion(41L)).isEqualTo(42L);
    }

    @Test
    @DisplayName("其它类型：UnsupportedOperationException，消息点名实际类型")
    void unsupportedVersionTypeIsRejected() {
        assertThatThrownBy(() -> JpaPersistExecutor.nextVersion("7"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining(String.class.getName());
    }
}
