package io.github.nextentity.tests.jpa.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/// 证明 SQL 快照工具本身可用：H2 能建表、写入与查询能被 `StatementInspector` 抓到。
///
/// 这是工具的**自检**，不是业务语义用例：它不依赖 nextentity 的任何实现，所以 jpa 执行器
/// 还没落地时也能独立证明「抓 SQL」这条链路是可信的。
class HibernateHarnessTest {

    @Test
    void recordsWriteAndReadSql() {
        try (HibernateHarness harness = HibernateHarness.start("harness_self_test", ProbeEntity.class)) {
            harness.clearSql();

            harness.inTransaction(entityManager -> entityManager.persist(new ProbeEntity(1L, "alpha")));
            List<String> writeSql = harness.drainSql();
            assertThat(writeSql).anySatisfy(sql -> assertThat(sql).containsIgnoringCase("insert into probe"));

            harness.clearSql();
            List<ProbeEntity> found = harness.entityManager()
                    .createQuery("select p from ProbeEntity p where p.name = :name", ProbeEntity.class)
                    .setParameter("name", "alpha")
                    .getResultList();

            assertThat(found).extracting(ProbeEntity::getId).containsExactly(1L);
            assertThat(harness.drainSql()).anySatisfy(sql -> assertThat(sql).containsIgnoringCase("from probe"));
        }
    }
}
