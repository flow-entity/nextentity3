package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.WindowArgumentConsistencyTest;
import org.junit.jupiter.api.AfterEach;

/// 把窗口参数（`0` = 不限行、负数被拒）的一致性用例接到 JPA 后端上（用例本体在 nextentity-testing 模块，本类只做接线）。
class ItemWindowArgumentConsistencyTest extends WindowArgumentConsistencyTest<ItemEntity> {

    private final JpaItemSupport support = new JpaItemSupport("item_window_argument");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }
}
