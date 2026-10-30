package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.GroupWindowConsistencyTest;
import org.junit.jupiter.api.AfterEach;

/// 把 groupBy 之后的分页窗口用例接到 JPA 后端上（H2 + 真 Hibernate）。
class ItemGroupWindowConsistencyTest extends GroupWindowConsistencyTest<ItemEntity> {

    private final JpaItemSupport support = new JpaItemSupport("item_group_window");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }
}
