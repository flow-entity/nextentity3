package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.EmptyAggregateConsistencyTest;
import io.github.nextentity.tests.jpa.support.PostgresItemSupport;
import io.github.nextentity.tests.jpa.support.PostgresL4Gate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 把零行与全 null 聚合语义的一致性用例接到真实 PostgreSQL 上：L4 的载体，用例本体仍是 `nextentity-testing` 的基类。
///
/// 夹具、期望值、装配路径都与 H2 侧（`ItemEmptyAggregateConsistencyTest`）完全相同，差别只有连接。
@ExtendWith(PostgresL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresItemEmptyAggregateIntegrationTest extends EmptyAggregateConsistencyTest<ItemEntity> {

    private final PostgresItemSupport support = new PostgresItemSupport();

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
