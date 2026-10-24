package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.tests.jpa.support.MySqlL4Gate;
import io.github.nextentity.tests.jpa.support.WriteRoundTripSupport;
import jakarta.persistence.OptimisticLockException;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// `@Version` 往返的真方言版：与 [ItemVersionRoundTripTest] 同一套声明与断言，只换连接。
///
/// 真方言上多一层风险：`version` 列的整数类型由 hbm2ddl 按 Java 类型选（`long` → bigint、
/// `short` → smallint），上界与回绕的位置与 H2 未必一致——两端都钉住才说明「+1 语义」与方言无关。
@ExtendWith(MySqlL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlItemVersionRoundTripIntegrationTest {

    private static final long ID = 1L;

    private static final int SHORT_UPPER_BOUND = Short.MAX_VALUE;

    /// 实际下发的整句（归一化后）：版本列既在 SET 里自增，也在 WHERE 里做乐观锁谓词。
    private static final String UPDATE_VERSIONED =
            "update versioned ve1_0 set version=?,name=? where ve1_0.id=? and ve1_0.version=?";

    private final WriteRoundTripSupport<VersionedEntity> support =
            WriteRoundTripSupport.onMySql(VersionedEntity.class, ShortVersionedEntity.class);

    private final EntityOperations<ShortVersionedEntity> shortOperations =
            support.operationsFor(ShortVersionedEntity.class);

    @BeforeEach
    void truncateAndClear() {
        support.truncate("VersionedEntity");
        support.truncate("ShortVersionedEntity");
        support.clear();
        support.clearSql();
    }

    /// 复位（AGENTS.md：集成测试启动容器后须在 `@AfterEach` 复位被改状态）：删掉本轮写入的行，
    /// 丢掉受管状态与语句账本，容器里的库回到空表。`@BeforeEach` 那条留作兜底——上一条用例
    /// 若崩在复位之前，它保证这一条仍从空表开始。
    @AfterEach
    void resetState() {
        support.truncate("VersionedEntity");
        support.truncate("ShortVersionedEntity");
        support.clear();
        support.clearSql();
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }

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

    @Test
    void updateStatementCarriesPrimaryKeyAndVersionPredicate() {
        insert(new VersionedEntity(ID, "v0"));
        long current = versionOf(ID);
        support.clearSql();

        update(new VersionedEntity(ID, "v1", current));

        List<String> updates = statements(support.drainSql(), "update versioned");
        assertThat(updates).containsExactly(UPDATE_VERSIONED);
    }

    @Test
    void shortVersionAtUpperBoundIsRejectedWithoutWritingAnything() {
        support.inTransaction(_ -> shortOperations.insert(new ShortVersionedEntity(ID, "s", (short) 0)));
        support.clear();
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
