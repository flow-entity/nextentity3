package io.github.nextentity.tests.jdbc;

import io.github.nextentity.testing.ReservedWordConsistencyTest;
import io.github.nextentity.testing.ReservedWordSupport;
import io.github.nextentity.tests.jdbc.support.L4Gate;
import io.github.nextentity.tests.jdbc.support.PostgresReservedWordSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 保留字标识符一致性用例的 PostgreSQL 容器接线：与 `ReservedWordTest` 同一实体、同一夹具。
@ExtendWith(L4Gate.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresReservedWordIntegrationTest extends ReservedWordConsistencyTest<ReservedWordEntity> {

    private final PostgresReservedWordSupport support =
            new PostgresReservedWordSupport("postgresreservedwordintegrationtest");

    @Override
    protected ReservedWordSupport<ReservedWordEntity> support() {
        return support;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
