package io.github.nextentity.tests.jdbc;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.StringOperatorConsistencyTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;

/// 把 `lower/upper/trim/length/substring` 与 NOT LIKE 的一致性用例接到 JDBC（H2 `MODE=MySQL`）后端上：
/// 用例本体在 `nextentity-testing`，本类只做接线。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ItemStringOperatorConsistencyTest extends StringOperatorConsistencyTest<ItemEntity> {

    private final JdbcItemSupport support = new JdbcItemSupport("itemstringoperatorconsistencytest");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
