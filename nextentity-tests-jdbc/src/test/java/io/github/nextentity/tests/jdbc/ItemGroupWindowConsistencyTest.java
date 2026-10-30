package io.github.nextentity.tests.jdbc;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.GroupWindowConsistencyTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;

/// 把 groupBy 之后的分页窗口用例接到 JDBC（H2 `MODE=MySQL`）后端上：窗口必须作用在组上，
/// 不是先取行再客户端分组。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ItemGroupWindowConsistencyTest extends GroupWindowConsistencyTest<ItemEntity> {

    private final JdbcItemSupport support = new JdbcItemSupport("itemgroupwindowconsistencytest");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
