package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.PredicateFuzzDifferentialTest;
import org.junit.jupiter.api.AfterEach;

/// 把谓词层的**属性测试**（seeded 概念树差分）接到 JPA 后端（H2）上：用例本体在
/// `nextentity-testing` 模块，本类只做接线。
///
/// 60 个 seed 的期望值全部由基类里的内存三值求值独立算出，本类不提供任何期望值，
/// 因此换后端不需要改用例——同一批 seed 同时对上 H2 与 MySQL，才是跨方言对拍的证据。
class ItemPredicateFuzzDifferentialTest extends PredicateFuzzDifferentialTest<ItemEntity> {

    private final JpaItemSupport support = new JpaItemSupport("item_predicate_fuzz_differential");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }
}
