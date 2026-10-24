package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.EmptyAggregateConsistencyTest;
import org.junit.jupiter.api.AfterEach;

/// 把零行与全 null 聚合语义的一致性用例接到 JPA 后端上（用例本体在 nextentity-testing 模块，本类只做接线）。
class ItemEmptyAggregateConsistencyTest extends EmptyAggregateConsistencyTest<ItemEntity> {

    private final JpaItemSupport support = new JpaItemSupport("item_empty_aggregate");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }
}
