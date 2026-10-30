package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.tests.jpa.support.PostgresEntityOperationsSupport;
import io.github.nextentity.tests.jpa.support.PostgresL4Gate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// `@Convert` 字段在真实 PostgreSQL 上的形态：写入按转换后形态落库，读回按内存形态，条件里用的也是内存形态。
///
/// 与 H2 侧 `ItemConvertConsistencyTest` 同一夹具、同一断言；本类只换方言。三条断言缺一不可：
/// ① 原生查询看到的是转换后形态；② 走 DSL 读回的是内存形态；③ 用内存形态做 `eq` 条件能命中。
@ExtendWith(PostgresL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresConvertIntegrationTest {

    private final PostgresEntityOperationsSupport<ConvertedEntity> support =
            new PostgresEntityOperationsSupport<>(ConvertedEntity.class);

    @BeforeEach
    void reset() {
        support.truncate("ConvertedEntity");
        support.clear();
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }

    @Test
    void convertAppliesOnWriteReadAndPredicate() {
        support.inTransaction(_ -> support.operations().insert(new ConvertedEntity(1L, "secret")));
        support.clear();

        String stored = (String) support.entityManager()
                .createNativeQuery("select payload from converted where id = 1")
                .getSingleResult();
        assertThat(stored).isEqualTo("v1:secret");

        List<ConvertedEntity> byId = support.operations()
                .query()
                .where(ConvertedEntity.idRef())
                .eq(1L)
                .list();
        assertThat(byId).extracting(ConvertedEntity::payload).containsExactly("secret");

        List<ConvertedEntity> byPayload = support.operations()
                .query()
                .where(ConvertedEntity.payloadRef())
                .eq("secret")
                .list();
        assertThat(byPayload).extracting(ConvertedEntity::id).containsExactly(1L);
    }
}
