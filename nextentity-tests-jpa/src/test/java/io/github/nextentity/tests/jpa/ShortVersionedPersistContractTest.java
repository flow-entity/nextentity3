package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.JpaEntityOperationsFactory;
import io.github.nextentity.tests.jpa.support.HibernateHarness;
import jakarta.persistence.EntityManager;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// 写入执行器对「非 Integer / Long 版本类型」的契约拒绝。
///
/// `@Version` 用 `short` 是 JPA 规范允许的形态，Hibernate 侧也完全支持（`ShortJavaType` 实现了
/// `VersionJavaType<Short>`，因此 [HibernateHarness] 能正常启动、元模型也把它报成 `isVersion()`）。
/// 但 `JpaPersistExecutor` 的版本自增只写了 Integer / Long 两条分支，其余类型显式拒绝——
/// 猜一个自增语义等于静默写坏乐观锁版本号，比抛异常危险。
///
/// 守护 `nextentity-jpa` 的 `jpa/exec/JpaPersistExecutor.java:159-160`。
class ShortVersionedPersistContractTest {

    private final HibernateHarness harness = HibernateHarness.start("short_versioned", ShortVersionedEntity.class);

    private final EntityOperations<ShortVersionedEntity> operations;

    ShortVersionedPersistContractTest() {
        this.operations = new JpaEntityOperationsFactory(harness.entityManager(), harness.entityManagerFactory())
                .operations(ShortVersionedEntity.class);
    }

    /// 拒绝发生在 `SET version = ?` 的构造阶段（`executeUpdate` 之前），事务会停在打开状态，
    /// 这里复位它，避免脏状态漏到下一个用例与 `close()`。
    @AfterEach
    void rollbackAndCloseHarness() {
        if (harness.entityManager().getTransaction().isActive()) {
            harness.entityManager().getTransaction().rollback();
        }
        harness.close();
    }

    /// `@Version` 为 `short` 的实体走 `update()` 必须在自增版本号处显式拒绝。
    ///
    /// 消息同时钉住「支持范围」与「实际类型」：`@Version 只支持 Integer / Long` 是可读的契约说明，
    /// 后面的 `getClass().getName()` 决定排障时能不能一眼看出是哪种版本类型——两者写错都会红。
    ///
    /// 触发点在 `setValues` 构造 SET 子句时，早于 `executeUpdate`，因此不需要库里有对应行：
    /// 用一个未纳管的实例即可，`entityManager.contains(entity)` 为 `false`，走的是同一条 `update` 路径。
    @Test
    void updateRejectsShortVersion() {
        ShortVersionedEntity detached = new ShortVersionedEntity(1L, "draft", (short) 0);

        assertThatThrownBy(() -> inTransaction(_ -> operations.update(detached)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("@Version 只支持 Integer / Long")
                .hasMessageContaining(Short.class.getName());
    }

    private void inTransaction(Consumer<EntityManager> work) {
        harness.inTransaction(work);
        harness.entityManager().clear();
    }
}
