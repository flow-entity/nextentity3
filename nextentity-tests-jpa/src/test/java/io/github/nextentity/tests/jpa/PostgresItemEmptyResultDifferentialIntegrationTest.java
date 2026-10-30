package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.EmptyResultDifferentialTest;
import io.github.nextentity.tests.jpa.support.PostgresItemSupport;
import io.github.nextentity.tests.jpa.support.PostgresL4Gate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 把「零行结果」的差分用例接到真实 PostgreSQL 上：L4 的载体，用例本体仍是 `nextentity-testing` 的基类。
///
/// 夹具、期望值、装配路径都与 H2 侧（`ItemEmptyResultDifferentialTest`）完全相同，差别只有连接：
/// 「零行时聚合投影仍返回一行、`sum`/`avg`/`min`/`max` 给 `NULL` 而 `count` 给 `0`」这组
/// 最容易被实现悄悄改掉的语义，在两套装置上逐格对拍。
@ExtendWith(PostgresL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresItemEmptyResultDifferentialIntegrationTest extends EmptyResultDifferentialTest<ItemEntity> {

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
