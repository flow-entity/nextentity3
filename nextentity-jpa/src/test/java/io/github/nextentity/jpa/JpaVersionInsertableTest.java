package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.jpa.entity.InsertableFalseVersioned;
import io.github.nextentity.jpa.entity.VersionedLedger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `@Version` 列上的 `@Column(insertable = false)` 在元数据期显式拒绝。
///
/// 版本初值是本层乐观锁协议的一部分：`insert` 写 0，之后的 `update(entity)` 用 `where version = 0` 命中。
/// Hibernate 遵守 `insertable = false` 把版本列从 `INSERT` 摘掉，库内版本停在 `NULL`——基本类型版本
/// 读回直接 `PropertyAccessException`，包装类型则让下一次 `update(entity)` 抛假的 `OptimisticLockException`
/// （丢失更新被伪装成并发冲突）。这个标志在版本列上无法被两后端一致实现（JDBC 侧同样拒绝），
/// 所以 fail-fast，文案与 JDBC 逐字一致。
class JpaVersionInsertableTest extends JpaTestSupport {

    @Test
    @DisplayName("@Version + @Column(insertable=false)：元数据期拒绝，不静默把版本写成 NULL")
    void insertableFalseVersionColumnIsRejected() {
        assertThatThrownBy(() -> meta().entityType(InsertableFalseVersioned.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("@Version 列不支持 @Column(insertable=false)")
                .hasMessageContaining(InsertableFalseVersioned.class.getName() + ".version");
    }

    @Test
    @DisplayName("对照：普通 @Version 列照常解析（拒绝只针对 insertable=false）")
    void plainVersionColumnIsStillAccepted() {
        assertThat(meta().entityType(VersionedLedger.class).version()).isNotNull();
    }
}
