package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.GroupCountConsistencyTest;
import org.junit.jupiter.api.AfterEach;

/// 把 groupBy 后 count/exists/窗口的一致性用例接到 JPA 后端上。
class ItemGroupCountConsistencyTest extends GroupCountConsistencyTest<ItemEntity> {

    private final JpaItemSupport support = new JpaItemSupport("item_group_count");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }
}
