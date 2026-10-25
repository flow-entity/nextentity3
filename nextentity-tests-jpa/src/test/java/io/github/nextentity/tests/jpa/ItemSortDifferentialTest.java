package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.NullOrdering;
import io.github.nextentity.testing.SortDifferentialTest;
import io.github.nextentity.tests.jpa.support.HibernateHarness;
import org.junit.jupiter.api.AfterEach;

/// 把排序家族的**差分**用例接到 JPA 后端上（用例本体在 nextentity-testing 模块，本类只做接线）。
class ItemSortDifferentialTest extends SortDifferentialTest<ItemEntity> {

    private final JpaItemSupport support = new JpaItemSupport("item_sort_differential");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }
    /// H2 通道的方言事实：`MODE=MySQL` 时 `ASC` 把 `null` 排最前，`MODE=PostgreSQL` 时排最后。
    /// 一致性基类的 oracle 不带方言假设，由后端从这一处声明。
    @Override
    protected NullOrdering nullOrdering() {
        return HibernateHarness.nullOrdering();
    }
}
