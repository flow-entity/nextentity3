package io.github.nextentity.tests.jpa.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/// 证明 SQL 快照工具本身可用：H2 能建表、写入与查询能被 `StatementInspector` 抓到。
///
/// 这是工具的**自检**，不是业务语义用例：它不依赖 nextentity 的任何实现，所以 jpa 执行器
/// 还没落地时也能独立证明「抓 SQL」这条链路是可信的。
///
/// 断言是**整句快照**（转小写 + 连续空白压一个空格）：自检要证明的正是「抓到的就是下发的那一条」，
/// 只断 `containsIgnoringCase("insert into probe")` 时，抓到的语句被截断、被重复记录、
/// 或把 `values (?,?)` 段丢掉都照样通过——而这些恰恰会让所有依赖它的 SQL 快照用例失真。
///
/// 快照只对 **H2 + 当前 Hibernate** 成立（别名 `pe1_0`、列序都来自 Hibernate 渲染）。
class HibernateHarnessTest {

    @Test
    void recordsWriteAndReadSql() {
        try (HibernateHarness harness = HibernateHarness.start("harness_self_test", ProbeEntity.class)) {
            harness.clearSql();

            harness.inTransaction(entityManager -> entityManager.persist(new ProbeEntity(1L, "alpha")));
            assertThat(normalized(harness.drainSql())).containsExactly("insert into probe (name,id) values (?,?)");

            harness.clearSql();
            List<ProbeEntity> found = harness.entityManager()
                    .createQuery("select p from ProbeEntity p where p.name = :name", ProbeEntity.class)
                    .setParameter("name", "alpha")
                    .getResultList();

            assertThat(found).extracting(ProbeEntity::getId).containsExactly(1L);
            assertThat(normalized(harness.drainSql()))
                    .containsExactly("select pe1_0.id,pe1_0.name from probe pe1_0 where pe1_0.name=?");
        }
    }

    /// 与 `ItemSqlSnapshotTest` 同一套归一化：转小写 + 连续空白压一个空格，其余字符原样比对。
    private static List<String> normalized(List<String> sqls) {
        return sqls.stream()
                .map(sql -> sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim())
                .toList();
    }
}
