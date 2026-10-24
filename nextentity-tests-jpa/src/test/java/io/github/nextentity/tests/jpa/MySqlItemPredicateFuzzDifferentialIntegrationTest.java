package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.PredicateFuzzDifferentialTest;
import io.github.nextentity.tests.jpa.support.MySqlItemSupport;
import io.github.nextentity.tests.jpa.support.MySqlL4Gate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 把谓词层的**属性测试**接到真实 MySQL 上：L4 的载体，用例本体仍是 `nextentity-testing` 的基类。
///
/// 夹具、内存三值求值、装配路径都与 H2 侧（`ItemPredicateFuzzDifferentialTest`）完全相同，
/// 差别只有连接：同一批 seed 同时对上两种方言，就是跨方言对拍的证据。
@ExtendWith(MySqlL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlItemPredicateFuzzDifferentialIntegrationTest extends PredicateFuzzDifferentialTest<ItemEntity> {

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
