package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ComparisonConsistencyTest;
import io.github.nextentity.testing.ConsistencySupport;
import org.junit.jupiter.api.AfterEach;

/// 把比较与集合算子的一致性用例接到 JPA 后端上（用例本体在 nextentity-testing 模块，本类只做接线）。
class ItemComparisonConsistencyTest extends ComparisonConsistencyTest<ItemEntity> {

    private final JpaItemSupport support = new JpaItemSupport("item_comparison");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }
}
