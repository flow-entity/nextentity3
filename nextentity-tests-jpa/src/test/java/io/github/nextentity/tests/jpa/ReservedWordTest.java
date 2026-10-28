package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ReservedWordConsistencyTest;
import io.github.nextentity.testing.ReservedWordSupport;
import org.junit.jupiter.api.AfterEach;

/// 保留字标识符一致性用例的 JPA/H2 接线：同一个实体、同一套夹具，只换后端装配。
class ReservedWordTest extends ReservedWordConsistencyTest<ReservedWordEntity> {

    private final JpaReservedWordSupport support = new JpaReservedWordSupport("reservedwordtest");

    @Override
    protected ReservedWordSupport<ReservedWordEntity> support() {
        return support;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }
}
