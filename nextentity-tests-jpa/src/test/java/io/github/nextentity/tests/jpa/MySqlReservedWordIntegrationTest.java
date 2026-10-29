package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ReservedWordConsistencyTest;
import io.github.nextentity.testing.ReservedWordSupport;
import io.github.nextentity.tests.jpa.support.MySqlL4Gate;
import io.github.nextentity.tests.jpa.support.MySqlReservedWordSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 保留字标识符一致性用例的 MySQL 容器接线：与 `ReservedWordTest` 同一实体、同一夹具。
@ExtendWith(MySqlL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlReservedWordIntegrationTest extends ReservedWordConsistencyTest<ReservedWordEntity> {

    private final MySqlReservedWordSupport support = new MySqlReservedWordSupport();

    @Override
    protected ReservedWordSupport<ReservedWordEntity> support() {
        return support;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
