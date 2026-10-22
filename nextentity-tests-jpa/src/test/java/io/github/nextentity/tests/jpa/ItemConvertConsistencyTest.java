package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.JpaEntityOperationsFactory;
import io.github.nextentity.tests.jpa.support.HibernateHarness;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// `@Convert` 字段的一致性：写入按转换后形态落库，读回按内存形态，条件里用的也是内存形态。
///
/// 判据（reviewer 口径）：该字段**参与 WHERE 时才**能证明参数绑定走了转换器，所以这里三条都断言：
/// ① 原生查询看到的是转换后形态；② 走 DSL 读回的是内存形态；③ 用内存形态做 `eq` 条件能命中。
class ItemConvertConsistencyTest {

    private final HibernateHarness harness = HibernateHarness.start("item_convert", ConvertedEntity.class);
    private final EntityOperations<ConvertedEntity> operations;

    ItemConvertConsistencyTest() {
        this.operations = new JpaEntityOperationsFactory(harness.entityManager(), harness.entityManagerFactory())
                .operations(ConvertedEntity.class);
    }

    @AfterEach
    void closeHarness() {
        harness.close();
    }

    @Test
    void convertAppliesOnWriteReadAndPredicate() {
        harness.inTransaction(_ -> operations.insert(new ConvertedEntity(1L, "secret")));
        harness.entityManager().clear();

        String stored = (String) harness.entityManager()
                .createNativeQuery("select payload from converted where id = 1")
                .getSingleResult();
        assertThat(stored).isEqualTo("v1:secret");

        List<ConvertedEntity> byId =
                operations.query().where(ConvertedEntity.idRef()).eq(1L).list();
        assertThat(byId).extracting(ConvertedEntity::payload).containsExactly("secret");

        List<ConvertedEntity> byPayload = operations
                .query()
                .where(ConvertedEntity.payloadRef())
                .eq("secret")
                .list();
        assertThat(byPayload).extracting(ConvertedEntity::id).containsExactly(1L);
    }
}
