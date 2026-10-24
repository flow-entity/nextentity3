package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.EmptyResultDifferentialTest;
import org.junit.jupiter.api.AfterEach;

/// 把「零行结果」的**差分**用例接到 JPA 后端（H2）上：用例本体在 `nextentity-testing`，本类只做接线。
///
/// 这一族的期望值全在「0 / `null` / 空列表 / 一行」的边界上，H2 与 MySQL 实测逐格一致，
/// 因此不需要按方言拆格。
class ItemEmptyResultDifferentialTest extends EmptyResultDifferentialTest<ItemEntity> {

    private final JpaItemSupport support = new JpaItemSupport("item_empty_result_differential");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }
}
