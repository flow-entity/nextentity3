package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.StringOperatorConsistencyTest;
import org.junit.jupiter.api.AfterEach;

/// 把字符串函数/NOT LIKE 的一致性用例接到 JPA 后端上（用例本体在 nextentity-testing 模块）。
class ItemStringOperatorConsistencyTest extends StringOperatorConsistencyTest<ItemEntity> {

    private final JpaItemSupport support = new JpaItemSupport("item_string_operator");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }
}
