package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.tests.jpa.support.PostgresL4Gate;
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

/// 写侧「写关联列」的真方言版：`update` 的 SET 子句**必须**带上外键列 `owner_id`，值为关联对象的主键；
/// 关联为 `null` 时写 `NULL`（JPA 侧靠 `CriteriaUpdate` 的实体值赋值，dirty checking 天然可置空）。
///
/// 这是 H2 侧 [io.github.nextentity.jpa.JpaPersistAssociationTest] 的容器通道对端：H2 的 PostgreSQL
/// 兼容模式与真 PostgreSQL 在「SET 带关联列、`SQL NULL` 生效」上恰好一致，但一致性此前没有被任何用例钉住。
/// 反向断言仍然保留：值必须等于**新**关联的主键（2），而不是库里的旧值（1）——只断言「SET 里出现
/// owner_id」挡不住「拿原值原样回写」这种假实现。
@ExtendWith(PostgresL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresAssetWriteAssociationIntegrationTest {

    private final WriteRoundTripSupport<AssetEntity> support =
            WriteRoundTripSupport.onPostgres(AssetEntity.class, OwnerEntity.class);

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
    @DisplayName("update：SET 子句带上 owner_id，值来自关联对象的主键而不是库里的旧值")
    void updateWritesAssociationColumn() {
        // 两个父实体：update 传入的关联指向 2，而库里是 1。只断言「SET 里出现 owner_id」会被
        // 「拿旧值原样回写」骗过，所以结果层再钉一次库里的值变成 2。
        seed();
        support.clearSql();

        support.inTransaction(_ -> operations().update(new AssetEntity(1L, "renamed", owner(2L, "owner-2"))));
        support.clear();

        List<String> updates = statements("update asset");
        assertThat(updates).hasSize(1);
        String statement = updates.getFirst();
        assertThat(statement).contains("set name=?").contains("where");
        assertThat(setClause(statement)).as("SET 子句必须写关联列：外键来自关联对象的 @Id").contains("owner_id=?");

        // 结果层：外键变成 2，且新名字已落库
        assertThat(ownerIdOf(1L)).isEqualTo(2L);
        assertThat(nameOf(1L)).isEqualTo("renamed");
    }

    @Test
    @DisplayName("update 把关联置空：SET 写 NULL，库里的外键被清掉（dirty checking 允许置空）")
    void updateWithNullAssociationClearsForeignKey() {
        seed();
        support.clearSql();

        support.inTransaction(_ -> operations().update(new AssetEntity(1L, "orphaned", null)));
        support.clear();

        assertThat(statements("update asset")).hasSize(1).allSatisfy(statement -> assertThat(setClause(statement))
                .contains("owner_id=?"));

        // 与 JDBC 后端的差异（它跳过 null 关联、无法置空）由 architect 在 USAGE 披露；
        // JPA 侧这里必须真的清掉，不能为了「对齐」弱化成跳过。
        assertThat(ownerIdOf(1L)).isNull();
        assertThat(nameOf(1L)).isEqualTo("orphaned");
    }

    @Test
    @DisplayName("insert：外键列照常写入")
    void insertWritesAssociationColumn() {
        seed();

        assertThat(statements("insert into asset"))
                .as("插入必须带 owner_id")
                .isNotEmpty()
                .allSatisfy(statement -> assertThat(statement).contains("owner_id"));
        assertThat(ownerIdOf(1L)).isEqualTo(1L);
    }

    /// 灌两行父实体 + 一行子实体（子实体走 `insert`，同时把 insert 的语句记进账本）。
    ///
    /// 两行父实体是刻意留的：第二个父实体让「update 传入的关联」与「库里已有的外键」可以指向
    /// 不同的行，否则结果层断言无法区分「写入了新关联」与「原样回写」。
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
    /// 整条 `contains` 之外的断言会假红，把一条真语义绑死在无关的实现细节上。
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
