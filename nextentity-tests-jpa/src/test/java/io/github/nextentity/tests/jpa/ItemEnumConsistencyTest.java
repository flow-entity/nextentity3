package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.EnumConsistencyTest;
import org.junit.jupiter.api.AfterEach;

/// 把枚举列的一致性用例接到 JPA 后端上（用例本体在 nextentity-testing 模块，本类只做接线）。
class ItemEnumConsistencyTest extends EnumConsistencyTest<ItemEntity> {

    private final JpaItemSupport support = new JpaItemSupport("item_enum");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }
}
