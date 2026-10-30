package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.AggregateDifferentialTest;
import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.tests.jpa.support.PostgresItemSupport;
import io.github.nextentity.tests.jpa.support.PostgresL4Gate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 把聚合家族的差分用例接到真实 PostgreSQL 上：L4 的载体，用例本体仍是 `nextentity-testing` 的基类。
///
/// 夹具、期望值、装配路径都与 H2 侧（`ItemAggregateDifferentialTest`）完全相同，差别只有连接：
/// 同一批动态用例在两套装置上各跑一遍，「跨方言结果集相同」是逐组逐值比出来的。
@ExtendWith(PostgresL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresItemAggregateDifferentialIntegrationTest extends AggregateDifferentialTest<ItemEntity> {

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
