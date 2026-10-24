package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.PredicateDifferentialTest;
import org.junit.jupiter.api.AfterEach;

/// 把谓词矩阵的**差分**用例接到 JPA 后端（H2）上：用例本体在 `nextentity-testing` 模块，本类只做接线。
///
/// 27 格的期望值全部由 `PredicateDifferentialTest` 里的内存三值逻辑独立算出，
/// 本类不提供任何期望值，因此换后端不需要改用例。
class ItemPredicateDifferentialTest extends PredicateDifferentialTest<ItemEntity> {

    private final JpaItemSupport support = new JpaItemSupport("item_predicate_differential");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }
}
