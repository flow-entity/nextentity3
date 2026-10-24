package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ArithmeticDifferentialTest;
import io.github.nextentity.testing.ConsistencySupport;
import org.junit.jupiter.api.AfterEach;

/// 把表达式算术的**差分**用例接到 JPA 后端上（用例本体在 nextentity-testing 模块，本类只做接线）。
class ItemArithmeticDifferentialTest extends ArithmeticDifferentialTest<ItemEntity> {

    private final JpaItemSupport support = new JpaItemSupport("item_arithmetic_differential");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }
}
