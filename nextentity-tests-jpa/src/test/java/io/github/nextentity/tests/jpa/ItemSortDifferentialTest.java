package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.SortDifferentialTest;
import org.junit.jupiter.api.AfterEach;

/// 把排序家族的**差分**用例接到 JPA 后端上（用例本体在 nextentity-testing 模块，本类只做接线）。
class ItemSortDifferentialTest extends SortDifferentialTest<ItemEntity> {

    private final JpaItemSupport support = new JpaItemSupport("item_sort_differential");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }
}
