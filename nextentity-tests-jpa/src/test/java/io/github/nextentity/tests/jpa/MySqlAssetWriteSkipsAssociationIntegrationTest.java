package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.tests.jpa.support.MySqlL4Gate;
import io.github.nextentity.tests.jpa.support.WriteRoundTripSupport;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 写侧「跳过关联列」的真方言版：`update` 的 SET 子句**不得**包含外键列 `owner_id`，
/// 且库里的外键值必须原样保留。
///
/// 这是 H2 侧 [io.github.nextentity.jpa.JpaPersistAssociationTest] 的容器通道对端。此前该语义
/// 只有 H2 证据：H2 的 MySQL 兼容模式与真 MySQL 在「`update … set` 带不带关联列」这件事上
/// 恰好一致，但一致性没有被任何用例钉住——把 `JpaPersistExecutor` 的跳过条件改坏，H2 会红，
/// 而真 MySQL 通道会静默放过。关联列被写进 SET 的后果是外键被覆盖成 `null`（实体上 `owner`
/// 为空时）或原值（非空时），前者会让读侧的嵌套路径查询当场丢行。
///
/// 两条断言各管一件事，缺一不可：
/// - 语句层：SET 子句里没有 `owner_id`（证明"跳过"这件事真的发生在渲染阶段）；
/// - 结果层：更新后 `owner_id` 仍是 1（证明库里没被覆盖——语句层的片段断言挡不住
///   "多写一条 UPDATE 再覆盖回来"这类改动）。为了让这条断言有区分力，夹具刻意灌**两个**
///   父实体、而 `update` 传入的关联指向第二个：跳过成立时库里留在 1，一旦关联列进了 SET
///   就会变成 2（或 `null`），两种情况都能被这一行断言捉住。
@ExtendWith(MySqlL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlAssetWriteSkipsAssociationIntegrationTest {

    private final WriteRoundTripSupport<AssetEntity> support =
            WriteRoundTripSupport.onMySql(AssetEntity.class, OwnerEntity.class);

    /// 容器在同一个 JVM 里共享：每个用例前清表、丢受管状态、清语句账本。
    @BeforeEach
    void reset() {
        support.truncate("AssetEntity");
        support.truncate("OwnerEntity");
        support.clear();
        support.clearSql();
    }

    /// 复位（AGENTS.md：集成测试启动容器后须在 `@AfterEach` 复位被改状态）。
    @AfterEach
    void resetAfter() {
        support.truncate("AssetEntity");
        support.truncate("OwnerEntity");
        support.clear();
        support.clearSql();
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }

    @Test
    @DisplayName("update：SET 子句不含 owner_id，库里的外键原样保留")
    void updateSkipsAssociationColumn() {
        // 两个父实体：update 传入的关联指向 2，而库里是 1。这样「跳过」与「原值覆盖」的
        // 结果才可区分——若 SET 带 owner_id，库里会变成 2（或 null），断言立刻红。
        seed();
        support.clearSql();

        support.inTransaction(_ -> operations().update(new AssetEntity(1L, "renamed", owner(2L, "owner-2"))));
        support.clear();

        List<String> updates = statements("update asset");
        assertThat(updates).hasSize(1);
        String statement = updates.getFirst();
        assertThat(statement).contains("set name=?").contains("where");
        assertThat(setClause(statement)).as("SET 子句只写标量列：关联列一旦进 SET，外键会被覆盖").doesNotContain("owner_id");

        // 结果层：外键仍是 1（不是 2、不是 null），且新名字已落库
        assertThat(ownerIdOf(1L)).isEqualTo(1L);
        assertThat(nameOf(1L)).isEqualTo("renamed");
    }

    @Test
    @DisplayName("update 把关联置空也不清库里的外键：跳过的是列，不是写 null")
    void updateWithNullAssociationKeepsForeignKey() {
        seed();
        support.clearSql();

        support.inTransaction(_ -> operations().update(new AssetEntity(1L, "still-owned", null)));
        support.clear();

        assertThat(statements("update asset")).hasSize(1).allSatisfy(statement -> assertThat(setClause(statement))
                .doesNotContain("owner_id"));

        // 实体上的 owner 为 null 时，跳过语义必须让库里的外键保持原值：
        // 若 SET 里带上 owner_id，这里会读回 null，读侧的嵌套路径查询随即丢行。
        assertThat(ownerIdOf(1L)).isEqualTo(1L);
        assertThat(nameOf(1L)).isEqualTo("still-owned");
    }

    @Test
    @DisplayName("insert：外键列照常写入（跳过只作用于 update）")
    void insertWritesAssociationColumn() {
        seed();

        assertThat(statements("insert into asset"))
                .as("插入必须带 owner_id，否则跳过语义被误扩展到了 insert")
                .isNotEmpty()
                .allSatisfy(statement -> assertThat(statement).contains("owner_id"));
        assertThat(ownerIdOf(1L)).isEqualTo(1L);
    }

    /// 灌两行父实体 + 一行子实体（子实体走 `insert`，同时把 insert 的语句记进账本）。
    ///
    /// 两行父实体是刻意留的：第二个父实体让「update 传入的关联」与「库里已有的外键」可以指向
    /// 不同的行，否则结果层断言无法区分「跳过了」与「写回了同一个值」。
    private void seed() {
        support.inTransaction(() -> {
            support.entityManager().persist(owner(1L, "owner-1"));
            support.entityManager().persist(owner(2L, "owner-2"));
            return null;
        });
        support.inTransaction(_ -> operations().insert(new AssetEntity(1L, "original", owner(1L, "owner-1"))));
        support.clear();
    }

    /// 关联列的目标：`update` 只用到它的主键，因此每次都新建（不查询、不依赖受管状态）。
    private OwnerEntity owner(long id, String name) {
        return new OwnerEntity(id, name);
    }

    /// 取归一化语句里 `set` 与 `where` 之间的片段。
    ///
    /// 只在 SET 上断言而不是整条语句：`where id=?` 未来若被优化成带 `owner_id` 的条件，
    /// 整条 `doesNotContain` 会假红，把一条真语义绑死在无关的实现细节上。
    private static String setClause(String statement) {
        int set = statement.indexOf(" set ");
        int where = statement.indexOf(" where ", set + 5);
        return statement.substring(set + 5, where < 0 ? statement.length() : where);
    }

    private EntityOperations<AssetEntity> operations() {
        return support.operations();
    }

    private Object ownerIdOf(long assetId) {
        return support.inTransaction(() -> support.entityManager()
                .createNativeQuery("select owner_id from asset where id = ?1")
                .setParameter(1, assetId)
                .getSingleResult());
    }

    private Object nameOf(long assetId) {
        return support.inTransaction(() -> support.entityManager()
                .createNativeQuery("select name from asset where id = ?1")
                .setParameter(1, assetId)
                .getSingleResult());
    }

    /// 归一化后按前缀筛出被测语句。
    private List<String> statements(String prefix) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        return support.drainSql().stream()
                .map(WriteRoundTripSupport::normalized)
                .filter(statement -> statement.startsWith(lowered))
                .toList();
    }
}
