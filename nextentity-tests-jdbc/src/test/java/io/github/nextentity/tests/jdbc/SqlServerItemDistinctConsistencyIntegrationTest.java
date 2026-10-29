package io.github.nextentity.tests.jdbc;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.DistinctConsistencyTest;
import io.github.nextentity.tests.jdbc.support.L4Gate;
import io.github.nextentity.tests.jdbc.support.SqlServerItemSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 与 `ItemDistinctConsistencyTest` 一一对应：夹具/期望完全相同，只把连接换成容器里的真 SQL Server。
@ExtendWith(L4Gate.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlServerItemDistinctConsistencyIntegrationTest extends DistinctConsistencyTest<ItemEntity> {

    private final SqlServerItemSupport support =
            new SqlServerItemSupport("sqlserveritemdistinctconsistencyintegrationtest");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
