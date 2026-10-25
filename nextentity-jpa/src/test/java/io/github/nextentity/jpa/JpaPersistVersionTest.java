package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.jpa.entity.Customer;
import io.github.nextentity.jpa.entity.VersionedLedger;
import io.github.nextentity.jpa.exec.JpaPersistExecutor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
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

    /// 无 `@Version` 的两条用例**自备** Customer 行（900 / 901）。
    ///
    /// 不复用 `JpaTestSupport` 的 10 / 11：那两行只在 `@BeforeAll` 灌一次，而本类要把名字改成
    /// `Alice2`，复用共享行会让「另一条用例看到 Alice 还是 Alice2」取决于执行顺序
    /// （上一版为此绕开了被改过的行，等于让断言去迁就耦合）。这里每条用例自己重建数据，
    /// 断言可以重新钉在「这一行的旧值没被动过」上。
    @BeforeEach
    void seedCustomersForUpdate() {
        inTx(entityManager -> {
            entityManager
                    .createQuery("delete from Customer c where c.id in (900, 901)")
                    .executeUpdate();
            entityManager.persist(new Customer(900L, 1L, "Alice", null));
            entityManager.persist(new Customer(901L, 2L, "Bob", null));
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
            Customer alice = entityManager.find(Customer.class, 900L);
            entityManager.detach(alice);
            alice.setName("Alice2");

            new JpaPersistExecutor<>(entityManager, meta(), Customer.class).update(alice);
            return null;
        });
        inTx(entityManager -> {
            assertThat(entityManager.find(Customer.class, 900L).getName()).isEqualTo("Alice2");
            // 丢掉主键条件的变异体会把 901 一起改掉（并且影响行数 2 → EntityNotFoundException）
            assertThat(entityManager.find(Customer.class, 901L).getName()).isEqualTo("Bob");
            return null;
        });
    }

    /// 无 `@Version` 的实体 update 一个不存在的 id：`verifyAffected` 的 `version == null` 一侧
    /// （`exec/JpaPersistExecutor.java:108-109`）。与「版本对不上 → `OptimisticLockException`」
    /// 互补：没有版本列时 0 行只可能意味着行不存在，所以异常类型必须是 `EntityNotFoundException`。
    @Test
    @DisplayName("无 @Version 实体 update 不存在的 id：EntityNotFoundException，消息点名主键")
    void updateMissingRowWithoutVersionThrowsEntityNotFound() {
        inTx(entityManager -> {
            Customer ghost = new Customer(999L, 1L, "ghost", null);

            assertThatThrownBy(() -> new JpaPersistExecutor<>(entityManager, meta(), Customer.class).update(ghost))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessage("实体 " + Customer.class.getName() + " 不存在: id=999");
            return null;
        });
        inTx(entityManager -> {
            // 影响行数为 0：没有新行被造出来，既有的两行也都没被写成 ghost。
            // 数据是 {@link #seedCustomersForUpdate()} 为本类自备的，所以两行都能直接钉住，
            // 不必再像上一版那样绕开「可能被别的用例改过」的行。
            assertThat(entityManager.find(Customer.class, 999L)).isNull();
            assertThat(entityManager
                            .createQuery("select count(c) from Customer c where c.name = 'ghost'", Long.class)
                            .getSingleResult())
                    .isZero();
            assertThat(entityManager.find(Customer.class, 900L).getName()).isEqualTo("Alice");
            assertThat(entityManager.find(Customer.class, 901L).getName()).isEqualTo("Bob");
            return null;
        });
    }

    @Test
    @DisplayName("已在持久化上下文的实体：执行器早退，变更只由脏检查写一次（版本 +1，不 +2）")
    void updateOnManagedEntityIsDelegatedToDirtyChecking() {
        inTx(entityManager -> {
            VersionedLedger ledger = entityManager.find(VersionedLedger.class, 1L);
            ledger.setName("dirty");

            persister(entityManager).update(ledger);

            // `:76` 早退分支的可观测契约：执行器自己一条 UPDATE 都不发，版本也不推进
            assertThat(drainSql()).noneMatch(sql -> sql.toLowerCase().startsWith("update"));
            assertThat(ledger.getVersion()).isZero();

            // flush 之后只该有脏检查自己发的那**一条整句** UPDATE（WHERE 用旧版本 0，`set` 里 name 在前、无别名）：
            // 执行器若不早退，会先发自己那条 `SET version=1` 的 UPDATE，flush 再按版本 1 发第二条，
            // 版本号被推到 2——整句集合与最终版本号两边都对不上。
            // 注意这条与下面 `updateWithNullVersionIsOptimisticLockConflict` 里那条**不是同一句**
            // （那条带别名 `vl1_0`、`set version=?,name=?`）：两句各由生产它的途径钉住，别合并成一句。
            entityManager.flush();
            assertThat(normalized(drainSql()))
                    .containsExactly("update versioned_ledger set name=?,version=? where id=? and version=?");
            assertThat(ledger.getVersion()).isEqualTo(1L);
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
    ///
    /// 等价性是**实测**的，不是推的：把 `:90` 整条删掉、把 `:89` 改成 `if (version == null)` 各跑一遍
    /// `-pl nextentity-jpa -am test` 与 `-pl nextentity-tests-jpa -am verify`（H2 + MySQL 两条通道），
    /// 两次都是零新增失败、零新增错误。要真让这两条变异体可杀，只能改生产代码（例如把 `detach`
    /// 挪到 `:76` 的早退之前），那属于 src/main 的语义变更，不在测试分支里做。
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
            // `SET` 里写 0 还是写 null 都没有行可写，两种实现的库状态与异常完全一致。
            //
            // 整句（执行器自己发的 UPDATE）：别名 `vl1_0`、`set` 里 version 在前、`where` 同时叠
            // 主键与旧版本值。旧断言只断片段 `version=?`，执行器把 `and version=?` 整段丢掉也照样绿。
            assertThat(normalized(drainSql()))
                    .containsExactly("update versioned_ledger vl1_0 set version=?,name=?"
                            + " where vl1_0.id=? and vl1_0.version=?");
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
    @DisplayName("update：已托管实体直接返回，不重复发 UPDATE，版本号保持原值")
    void updateIgnoresManagedEntity() {
        inTx(entityManager -> {
            VersionedLedger ledger = entityManager.find(VersionedLedger.class, 1L);
            long versionBefore = ledger.getVersion();
            ledger.setName("managed-noop");
            drainSql();

            persister(entityManager).update(ledger);

            // `updateOne` 开头的 `contains` 判断把托管实体当作「Hibernate 会自己 flush」直接返回。
            // 去掉这个判断，这里就会多发一条 UPDATE 并把版本自增，调用方看到版本跳号。
            assertThat(drainSql()).as("托管实体的 update 不应发出 SQL").isEmpty();
            assertThat(ledger.getVersion()).as("托管实体的版本号不应被改动").isEqualTo(versionBefore);
            return null;
        });
    }

    private JpaPersistExecutor<VersionedLedger> persister(EntityManager entityManager) {
        return new JpaPersistExecutor<>(entityManager, meta(), VersionedLedger.class);
    }
}
