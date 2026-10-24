package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.HavingDifferentialTest;
import io.github.nextentity.tests.jpa.support.MySqlItemSupport;
import io.github.nextentity.tests.jpa.support.MySqlL4Gate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 把「分组后过滤」的差分用例接到真实 MySQL 上：L4 的载体，用例本体仍是 `nextentity-testing` 的基类。
///
/// 夹具、期望值、装配路径都与 H2 侧（`ItemHavingDifferentialTest`）完全相同，差别只有连接：
/// 同一批动态用例在两套装置上各跑一遍，「`having` 的分组/`NULL`/`UNKNOWN` 语义跨方言相同」
/// 是逐组逐值比出来的，而不是靠手算常量声称的。
@ExtendWith(MySqlL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlItemHavingDifferentialIntegrationTest extends HavingDifferentialTest<ItemEntity> {

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
