package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.HavingDifferentialTest;
import org.junit.jupiter.api.AfterEach;

/// 把「分组后过滤」的**差分**用例接到 JPA 后端（H2）上：用例本体在 `nextentity-testing`，本类只做接线。
///
/// H2 与 MySQL 的 `having` 语义逐格一致（含 `NULL` 组归属与 `null` 聚合的 `UNKNOWN` 排除），
/// 因此这一族全部留在后端中立的基类里，不需要按方言拆格。
class ItemHavingDifferentialTest extends HavingDifferentialTest<ItemEntity> {

    private final JpaItemSupport support = new JpaItemSupport("item_having_differential");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }
}
