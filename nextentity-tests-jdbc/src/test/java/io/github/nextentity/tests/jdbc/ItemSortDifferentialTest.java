package io.github.nextentity.tests.jdbc;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.SortDifferentialTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;

/// 把 差分用例接到 JDBC（H2 `MODE=MySQL`）后端上：用例本体在 `nextentity-testing`，本类只做接线。
/// 库名按类名小写单独起，避免与其它接线类共用同一个内存库而互相看到对方的夹具。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ItemSortDifferentialTest extends SortDifferentialTest<ItemEntity> {

    private final JdbcItemSupport support = new JdbcItemSupport("itemsortdifferentialtest");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
