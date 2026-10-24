package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.tests.jpa.support.WriteRoundTripSupport;
import jakarta.persistence.OptimisticLockException;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// `@Version` 的**往返差分**：声明「写几次、每次期望的版本号是多少」，再读回来对。
///
/// 期望值不来自实现：`@Version` 的初值与步长由用例自己声明（插入后 0，之后每次 +1），
/// 读回侧是原生/JPQL 查询。既有 `ItemVersionConsistencyTest` 只断言「变大」与「陈旧版本失败」，
/// 这里把**精确值**与**失败时一个字都没写**钉住——「+2」「跳号」「失败后部分列被改」都能过前者的断言。
class ItemVersionRoundTripTest {

    private static final long ID = 1L;

    /// `short` 版本的上界：JPA 允许，Hibernate 也支持，但本库的自增只实现了 Integer / Long。
    private static final int SHORT_UPPER_BOUND = Short.MAX_VALUE;

    /// 实际下发的整句（归一化后）：版本列既在 SET 里自增，也在 WHERE 里做乐观锁谓词。
    private static final String UPDATE_VERSIONED =
            "update versioned ve1_0 set version=?,name=? where ve1_0.id=? and ve1_0.version=?";

    private final WriteRoundTripSupport<VersionedEntity> support =
            WriteRoundTripSupport.onH2("item_version_round_trip", VersionedEntity.class, ShortVersionedEntity.class);

    private final EntityOperations<ShortVersionedEntity> shortOperations =
            support.operationsFor(ShortVersionedEntity.class);

    @AfterEach
    void closeSupport() {
        support.close();
    }

    /// 插入后版本为 0，之后每次 update 恰好 +1（不是 +2、不跳号），且列值跟着声明走。
    @Test
    void versionStartsAtZeroAndIncrementsByOnePerUpdate() {
        insert(new VersionedEntity(ID, "v0"));
        assertThat(versionOf(ID)).isZero();

        update(new VersionedEntity(ID, "v1", versionOf(ID)));
        assertThat(versionOf(ID)).isEqualTo(1);
        assertThat(nameOf(ID)).isEqualTo("v1");

        update(new VersionedEntity(ID, "v2", versionOf(ID)));
        assertThat(versionOf(ID)).isEqualTo(2);
        assertThat(nameOf(ID)).isEqualTo("v2");
    }

    /// 陈旧版本号必须抛 `OptimisticLockException`，且那次更新一个字都不能落库。
    ///
    /// 文案是本库自己拼的（`实体 <类名> 不存在: id=1, version=0`），所以断言里钉住 `version=0`
    /// 这一段的实际值——排障时要能一眼看出「用哪个版本号撞的」。
    @Test
    void staleVersionUpdateIsRejectedAndWritesNothing() {
        insert(new VersionedEntity(ID, "v0"));
        long stale = versionOf(ID);
        update(new VersionedEntity(ID, "v1", stale));
        long fresh = versionOf(ID);
        assertThat(fresh).isEqualTo(stale + 1);

        assertThatThrownBy(() -> update(new VersionedEntity(ID, "stale", stale)))
                .isInstanceOf(OptimisticLockException.class)
                .hasMessageContaining("id=" + ID)
                .hasMessageContaining("version=" + stale);

        assertThat(versionOf(ID)).isEqualTo(fresh);
        assertThat(nameOf(ID)).isEqualTo("v1");
    }

    /// 语句骨架：`update versioned … set …` 同时带主键条件与版本条件。
    ///
    /// 少了 `and version=?` 就不再是乐观锁，而是「谁后写谁生效」——两个并发请求都成功、都没报错，
    /// 只有比值断言时完全看不出来。
    @Test
    void updateStatementCarriesPrimaryKeyAndVersionPredicate() {
        insert(new VersionedEntity(ID, "v0"));
        long current = versionOf(ID);
        support.clearSql();

        update(new VersionedEntity(ID, "v1", current));

        List<String> updates = statements(support.drainSql(), "update versioned");
        assertThat(updates).containsExactly(UPDATE_VERSIONED);
    }

    /// 版本号顶到 `short` 上界时，自增必须显式拒绝，且**一条语句都不下发**。
    ///
    /// 这条盯的是回绕：若实现按 `short` 做 `version + 1`，`Short.MAX_VALUE` 会静默变成
    /// `Short.MIN_VALUE`，乐观锁从此永远通过（旧版本号再也撞不上），而且没有任何异常。
    /// 拒绝发生在 SET 子句构造阶段（`executeUpdate` 之前），所以库里的行必须原封不动：
    /// 版本仍是上界、`name` 仍是插入时的值。
    @Test
    void shortVersionAtUpperBoundIsRejectedWithoutWritingAnything() {
        support.inTransaction(_ -> shortOperations.insert(new ShortVersionedEntity(ID, "s", (short) 0)));
        support.clear();
        // 把库内版本顶到上界：模拟「版本号已经涨到 Short.MAX_VALUE」的历史数据
        support.inTransaction(entityManager -> entityManager
                .createNativeQuery("update short_versioned set version = ? where id = ?")
                .setParameter(1, SHORT_UPPER_BOUND)
                .setParameter(2, ID)
                .executeUpdate());
        support.clear();
        support.clearSql();

        assertThatThrownBy(() -> support.inTransaction(_ ->
                        shortOperations.update(new ShortVersionedEntity(ID, "changed", (short) SHORT_UPPER_BOUND))))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("@Version 只支持 Integer / Long")
                .hasMessageContaining(Short.class.getName());

        assertThat(statements(support.drainSql(), "update short_versioned")).isEmpty();
        assertThat(shortVersionOf(ID)).isEqualTo((short) SHORT_UPPER_BOUND);
        assertThat(shortNameOf(ID)).isEqualTo("s");
    }

    private void insert(VersionedEntity entity) {
        support.inTransaction(_ -> support.operations().insert(entity));
        support.clear();
    }

    private void update(VersionedEntity entity) {
        support.inTransaction(_ -> support.operations().update(entity));
        support.clear();
    }

    private long versionOf(long id) {
        support.clear();
        return support.entityManager()
                .createQuery("select v.version from VersionedEntity v where v.id = :id", Long.class)
                .setParameter("id", id)
                .getSingleResult();
    }

    private String nameOf(long id) {
        support.clear();
        return support.entityManager()
                .createQuery("select v.name from VersionedEntity v where v.id = :id", String.class)
                .setParameter("id", id)
                .getSingleResult();
    }

    private short shortVersionOf(long id) {
        support.clear();
        return ((Number) support.entityManager()
                        .createNativeQuery("select version from short_versioned where id = ?")
                        .setParameter(1, id)
                        .getSingleResult())
                .shortValue();
    }

    private String shortNameOf(long id) {
        support.clear();
        return (String) support.entityManager()
                .createNativeQuery("select name from short_versioned where id = ?")
                .setParameter(1, id)
                .getSingleResult();
    }

    /// 归一化成一句话（小写 + 空白压缩）后按前缀筛，断言侧就能整句比对。
    private static List<String> statements(List<String> sql, String prefix) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        return sql.stream()
                .map(WriteRoundTripSupport::normalized)
                .filter(statement -> statement.startsWith(lowered))
                .toList();
    }
}
