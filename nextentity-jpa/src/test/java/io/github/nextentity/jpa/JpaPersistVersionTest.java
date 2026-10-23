package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.jpa.entity.Customer;
import io.github.nextentity.jpa.entity.VersionedLedger;
import io.github.nextentity.jpa.exec.JpaPersistExecutor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `JpaPersistExecutor.update` 的三件事：版本列自增、`WHERE` 用**旧**版本值、无版本实体只按主键定位。
///
/// 守护的变异体（`exec/JpaPersistExecutor.java`）：
/// - `:144` `whereClause` 返回 `null`：无 `@Version` 的实体丢了主键条件 → 一次 `update` 会改掉全表；
/// - `:156` `nextVersion` 的 `instanceof Long` 判反 → 落进 `UnsupportedOperationException`；
/// - `:157` Long 版本 `current + 1` → `current - 1`，或返回 `null` → 版本列不增反减/被清空。
///
/// `ShopOrder` 的版本列是 `int`（只覆盖 Integer 分支），本类改用 {@link VersionedLedger} 的
/// `@Version Long` 把 Long 分支走通。
class JpaPersistVersionTest extends JpaTestSupport {

    @BeforeEach
    void seedLedgers() {
        inTx(entityManager -> {
            entityManager.createQuery("delete from VersionedLedger").executeUpdate();
            entityManager.persist(new VersionedLedger(1L, "opening", null));
            entityManager.persist(new VersionedLedger(2L, "second", null));
            return null;
        });
        drainSql();
    }

    @Test
    @DisplayName("update：Long 版本列 0 → 1，并把新版本号回写到入参对象")
    void updateIncrementsLongVersion() {
        inTx(entityManager -> {
            VersionedLedger ledger = entityManager.find(VersionedLedger.class, 1L);
            assertThat(ledger.getVersion()).isEqualTo(0L);
            entityManager.detach(ledger);
            ledger.setName("renamed");

            persister(entityManager).update(ledger);

            assertThat(ledger.getVersion()).isEqualTo(1L);
            return null;
        });
        inTx(entityManager -> {
            VersionedLedger reloaded = entityManager.find(VersionedLedger.class, 1L);
            assertThat(reloaded.getName()).isEqualTo("renamed");
            assertThat(reloaded.getVersion()).isEqualTo(1L);
            return null;
        });
    }

    @Test
    @DisplayName("update：WHERE 叠了旧版本值，版本对不上就是乐观锁冲突")
    void updateUsesOldVersionInWhereClause() {
        inTx(entityManager -> {
            VersionedLedger ledger = entityManager.find(VersionedLedger.class, 1L);
            entityManager.detach(ledger);
            // 内存里的版本号与库里（0）不一致：WHERE 若只按主键定位就会静默改成功
            ledger.setVersion(7L);
            ledger.setName("stale");

            assertThatThrownBy(() -> persister(entityManager).update(ledger))
                    .isInstanceOf(OptimisticLockException.class)
                    .hasMessageContaining("version=7");
            return null;
        });
        inTx(entityManager -> {
            VersionedLedger reloaded = entityManager.find(VersionedLedger.class, 1L);
            assertThat(reloaded.getName()).isEqualTo("opening");
            assertThat(reloaded.getVersion()).isEqualTo(0L);
            return null;
        });
    }

    @Test
    @DisplayName("无 @Version 实体：只按主键定位，不会误改其它行")
    void updateWithoutVersionFiltersByPrimaryKey() {
        inTx(entityManager -> {
            Customer alice = entityManager.find(Customer.class, 10L);
            entityManager.detach(alice);
            alice.setName("Alice2");

            new JpaPersistExecutor<>(entityManager, meta(), Customer.class).update(alice);
            return null;
        });
        inTx(entityManager -> {
            assertThat(entityManager.find(Customer.class, 10L).getName()).isEqualTo("Alice2");
            // 丢掉主键条件的变异体会把 Bob 一起改掉（并且影响行数 2 → EntityNotFoundException）
            assertThat(entityManager.find(Customer.class, 11L).getName()).isEqualTo("Bob");
            return null;
        });
    }

    @Test
    @DisplayName("已在持久化上下文的实体：执行器早退，交给脏检查（不发 UPDATE、不推进版本）")
    void updateOnManagedEntityIsDelegatedToDirtyChecking() {
        inTx(entityManager -> {
            VersionedLedger ledger = entityManager.find(VersionedLedger.class, 1L);
            ledger.setName("dirty");

            persister(entityManager).update(ledger);

            assertThat(drainSql()).noneMatch(sql -> sql.toLowerCase().startsWith("update"));
            assertThat(ledger.getVersion()).isEqualTo(0L);
            return null;
        });
        inTx(entityManager -> {
            VersionedLedger reloaded = entityManager.find(VersionedLedger.class, 1L);
            assertThat(reloaded.getName()).isEqualTo("dirty");
            assertThat(reloaded.getVersion()).isEqualTo(1L);
            return null;
        });
    }

    /// 入参对象在 `update` 前后都不是受管状态。
    ///
    /// 这条用例**杀不掉** `:89`（条件取反）与 `:90`（删除 `detach` 调用）两条变异体：
    /// 走到 `:89` 时 `entityManager.contains(entity)` 刚在 `:76` 判过为 `false`，而 JPA 规定
    /// 「对未与持久化上下文关联的实体调用 `detach` 会被忽略」，因此该调用与它的条件都不改变
    /// 任何可观测状态。这里只把「更新完仍然是 detached」这个契约钉住，不假装覆盖了那两条变异体。
    @Test
    @DisplayName("update 之后入参对象仍然是 detached")
    void updateLeavesEntityDetached() {
        inTx(entityManager -> {
            VersionedLedger ledger = entityManager.find(VersionedLedger.class, 1L);
            entityManager.detach(ledger);
            ledger.setName("renamed");

            persister(entityManager).update(ledger);

            assertThat(entityManager.contains(ledger)).isFalse();
            return null;
        });
    }

    /// 版本号缺失（`@Version` 包装类型为 `null`）时：`SET` 与 `WHERE` 都按 `null` 走，行数必然 0。
    ///
    /// 这条用例覆盖 `nextVersion` 的 `versionValue == null` 分支（`:151`），但**杀不掉**它的
    /// 「返回 null」变异体：`WHERE version = null` 本身就不匹配任何行，`SET version = 0` 与
    /// `SET version = null` 在 0 行更新下没有区别，两种实现都抛同一个 `OptimisticLockException`。
    @Test
    @DisplayName("版本号为 null 的实体：乐观锁冲突，库里那一行不动")
    void updateWithNullVersionIsOptimisticLockConflict() {
        inTx(entityManager -> {
            VersionedLedger stale = new VersionedLedger(1L, "stale", null);

            assertThatThrownBy(() -> persister(entityManager).update(stale))
                    .isInstanceOf(OptimisticLockException.class)
                    .hasMessageContaining("version=null");
            // 等价性论证的前提就在这里：`builder.equal(path, null)` 渲染成 `version=?`（绑 null），
            // 不是 `version is null`；NULL 比较恒为 UNKNOWN，任何行都命中不了 → 影响行数恒为 0，
            // `SET` 里写 0 还是写 null 都没有行可写，两种实现的库状态与异常完全一致
            assertThat(drainSql()).anySatisfy(sql -> assertThat(sql).containsIgnoringCase("version=?"));
            return null;
        });
        inTx(entityManager -> {
            VersionedLedger reloaded = entityManager.find(VersionedLedger.class, 1L);
            assertThat(reloaded.getName()).isEqualTo("opening");
            assertThat(reloaded.getVersion()).isEqualTo(0L);
            return null;
        });
    }

    private JpaPersistExecutor<VersionedLedger> persister(EntityManager entityManager) {
        return new JpaPersistExecutor<>(entityManager, meta(), VersionedLedger.class);
    }
}
