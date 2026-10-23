package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.HavingConsistencyTest;
import org.junit.jupiter.api.AfterEach;

/// 把分组后过滤与多列分组的一致性用例接到 JPA 后端上（用例本体在 nextentity-testing 模块，本类只做接线）。
class ItemHavingConsistencyTest extends HavingConsistencyTest<ItemEntity> {

    private final JpaItemSupport support = new JpaItemSupport("item_having");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }
}
