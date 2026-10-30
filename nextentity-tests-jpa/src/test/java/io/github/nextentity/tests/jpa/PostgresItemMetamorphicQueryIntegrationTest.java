package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.MetamorphicQueryTest;
import io.github.nextentity.tests.jpa.support.PostgresItemSupport;
import io.github.nextentity.tests.jpa.support.PostgresL4Gate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 把**变形关系**用例接到真实 PostgreSQL 上：L4 的载体，用例本体仍是 `nextentity-testing` 的基类。
///
/// 夹具、关系、装配路径都与 H2 侧（`ItemMetamorphicQueryTest`）完全相同，差别只有连接。
/// TLP 在真库上的价值尤其高：三值逻辑（`NOT` 与 `NULL` 的交互）恰恰是 H2 与 PostgreSQL 最容易分叉的地方，
/// 同一条关系同时对上两种方言，就是「关系不依赖方言」的证据。
@ExtendWith(PostgresL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresItemMetamorphicQueryIntegrationTest extends MetamorphicQueryTest<ItemEntity> {

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
