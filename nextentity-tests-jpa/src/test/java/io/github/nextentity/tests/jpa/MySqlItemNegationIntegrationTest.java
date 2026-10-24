package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.NegationConsistencyTest;
import io.github.nextentity.tests.jpa.support.MySqlItemSupport;
import io.github.nextentity.tests.jpa.support.MySqlL4Gate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 把 `not()` 取反的一致性用例接到真实 MySQL 上：L4 的载体，用例本体仍是 `nextentity-testing` 的基类。
///
/// core 消去双重否定是纯语法重写，真方言要证明的是消去后生成的 SQL 仍然正确。
@ExtendWith(MySqlL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlItemNegationIntegrationTest extends NegationConsistencyTest<ItemEntity> {

    private final MySqlItemSupport support = new MySqlItemSupport();

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
