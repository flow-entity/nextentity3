package io.github.nextentity.jpa.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `@Version` 递增的唯一实现点（`JpaPersistExecutor.nextVersion`）。
///
/// 用例各自钉一个分支：`null` 起步值按属性类型给零值（`Short` 字段不能拿到 `Integer 0`）、
/// 三种版本类型各自加一、其它类型拒绝并点名类型。不需要 Hibernate——这个方法是纯函数。
class JpaPersistExecutorNextVersionTest {

    @Test
    @DisplayName("Integer 版本列为 null（新实体）：从 0 起算，返回 Integer 而不是 null")
    void nullVersionStartsAtZero() {
        assertThat(JpaPersistExecutor.nextVersion(null, int.class)).isEqualTo(0);
    }

    @Test
    @DisplayName("Integer 版本：加一，且走 Integer 分支（不是从 0 重新起算）")
    void integerVersionIncrements() {
        assertThat(JpaPersistExecutor.nextVersion(41, Integer.class)).isEqualTo(42);
    }

    @Test
    @DisplayName("Long 版本：加一，且走 Long 分支（不丢精度、不从 0 重新起算）")
    void longVersionIncrements() {
        assertThat(JpaPersistExecutor.nextVersion(41L, Long.class)).isEqualTo(42L);
    }

    @Test
    @DisplayName("Long 版本列为 null：零值也是 Long（写回 Long 字段不抛 IllegalArgumentException）")
    void nullLongVersionStartsAtZeroLong() {
        assertThat(JpaPersistExecutor.nextVersion(null, long.class)).isEqualTo(0L);
    }

    @Test
    @DisplayName("Short 版本：加一，且走 Short 分支（JDBC 后端已支持 short/Short，JPA 侧不落队）")
    void shortVersionIncrements() {
        assertThat(JpaPersistExecutor.nextVersion((short) 41, Short.class)).isEqualTo((short) 42);
    }

    @Test
    @DisplayName("Short 版本列为 null：零值必须是 Short（Integer 0 写回 Short 字段会抛 IllegalArgumentException）")
    void nullShortVersionStartsAtZeroShort() {
        assertThat(JpaPersistExecutor.nextVersion(null, short.class)).isEqualTo((short) 0);
    }

    @Test
    @DisplayName("三种数值型的上界：显式拒绝，绝不静默回绕成负数（回绕会让乐观锁永远通过）")
    void versionAtTypeUpperBoundIsRejected() {
        assertVersionUpperBoundRejected(Integer.MAX_VALUE, Integer.class, "Integer");
        assertVersionUpperBoundRejected(Long.MAX_VALUE, long.class, "Long");
        assertVersionUpperBoundRejected((short) Short.MAX_VALUE, Short.class, "Short");
    }

    @Test
    @DisplayName("其它类型：UnsupportedOperationException，消息点名实际类型")
    void unsupportedVersionTypeIsRejected() {
        assertThatThrownBy(() -> JpaPersistExecutor.nextVersion("7", String.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining(String.class.getName());
    }

    private static void assertVersionUpperBoundRejected(Object value, Class<?> type, String typeName) {
        assertThatThrownBy(() -> JpaPersistExecutor.nextVersion(value, type))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("@Version")
                .hasMessageContaining(typeName)
                .hasMessageContaining("上界")
                .hasMessageContaining("回绕");
    }
}
