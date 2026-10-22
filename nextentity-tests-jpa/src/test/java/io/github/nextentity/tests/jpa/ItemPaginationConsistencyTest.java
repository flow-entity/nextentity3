package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.PaginationConsistencyTest;
import org.junit.jupiter.api.AfterEach;

/// 把分页 total 时机的一致性用例接到 JPA 后端上（用例本体在 nextentity-testing 模块，本类只做接线）。
class ItemPaginationConsistencyTest extends PaginationConsistencyTest<ItemEntity> {

    private final JpaItemSupport support = new JpaItemSupport("item_pagination");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }
}
