package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.ProjectionReplacementDifferentialTest;
import org.junit.jupiter.api.AfterEach;

/// 把「一次查询只有一份投影」的**差分**用例接到 JPA 后端上（用例本体在 `nextentity-testing`，本类只做接线）。
class ItemProjectionReplacementDifferentialTest extends ProjectionReplacementDifferentialTest<ItemEntity> {

    private final JpaItemSupport support = new JpaItemSupport("item_projection_replacement");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }
}
