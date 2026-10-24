package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ComparisonConsistencyTest;
import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.tests.jpa.support.MySqlItemSupport;
import io.github.nextentity.tests.jpa.support.MySqlL4Gate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 把比较与集合算子的一致性用例接到真实 MySQL 上：L4 的载体，用例本体仍是 `nextentity-testing` 的基类。
///
/// 夹具、期望值、装配路径都与 H2 侧（`ItemComparisonConsistencyTest`）完全相同，差别只有连接。
/// 一个 JVM 共用一个容器（`MySqlL4Container`），本类自己持有一个 `SessionFactory`：
/// 用例类之间因此互不干扰，也不会为每个类重启一次容器。
@ExtendWith(MySqlL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlItemComparisonIntegrationTest extends ComparisonConsistencyTest<ItemEntity> {

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
