package io.github.nextentity.tests.jdbc;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.StringOperatorConsistencyTest;
import io.github.nextentity.tests.jdbc.support.L4Gate;
import io.github.nextentity.tests.jdbc.support.PostgresItemSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 与 `MySqlItemStringOperatorConsistencyIntegrationTest` 一一对应：夹具/期望完全相同，只把连接换成
/// 容器里的真 PostgreSQL（方言矩阵要求同批用例、条数相等）。
@ExtendWith(L4Gate.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresItemStringOperatorConsistencyIntegrationTest extends StringOperatorConsistencyTest<ItemEntity> {

    private final PostgresItemSupport support =
            new PostgresItemSupport("postgresitemstringoperatorconsistencyintegrationtest");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
