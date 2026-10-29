package io.github.nextentity.tests.jdbc;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.NullOrdering;
import io.github.nextentity.testing.WindowArgumentConsistencyTest;
import io.github.nextentity.tests.jdbc.support.L4Gate;
import io.github.nextentity.tests.jdbc.support.PostgresItemSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 与 `ItemWindowArgumentConsistencyTest` 一一对应：夹具/期望完全相同，只把连接换成容器里的真 PostgreSQL。
@ExtendWith(L4Gate.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresItemWindowArgumentConsistencyIntegrationTest extends WindowArgumentConsistencyTest<ItemEntity> {

    private final PostgresItemSupport support =
            new PostgresItemSupport("postgresitemwindowargumentconsistencyintegrationtest");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }

    /// PostgreSQL 升序把 `null` 排最后（MySQL 相反），差分 oracle 从这一处取方言事实。
    @Override
    protected NullOrdering nullOrdering() {
        return NullOrdering.NULLS_LAST;
    }
}
