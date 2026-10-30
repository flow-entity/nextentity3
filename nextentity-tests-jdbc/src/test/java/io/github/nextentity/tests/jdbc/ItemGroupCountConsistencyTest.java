package io.github.nextentity.tests.jdbc;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.GroupCountConsistencyTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;

/// 把 groupBy 之后 count()/exists() 的一致性用例接到 JDBC（H2 `MODE=MySQL`）后端上。
///
/// 这个能力此前在 JDBC 侧没有接线：`nextentity-jdbc` 的单测里有分组 SQL 形状用例，但
/// 「组数对不对」没有任何一致/差分证据，等于能力面缩小了也没人发现。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ItemGroupCountConsistencyTest extends GroupCountConsistencyTest<ItemEntity> {

    private final JdbcItemSupport support = new JdbcItemSupport("itemgroupcountconsistencytest");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
