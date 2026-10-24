package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.EmptyResultConsistencyTest;
import org.junit.jupiter.api.AfterEach;

/// 把零行聚合/列表语义的一致性用例接到 JPA 后端上（用例本体在 nextentity-testing 模块，本类只做接线）。
class ItemEmptyResultConsistencyTest extends EmptyResultConsistencyTest<ItemEntity> {

    private final JpaItemSupport support = new JpaItemSupport("item_empty_result");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }
}
