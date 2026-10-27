package io.github.nextentity.tests.jdbc;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.EnumConsistencyTest;
import io.github.nextentity.tests.jdbc.support.MySqlItemSupport;
import io.github.nextentity.tests.jdbc.support.MySqlL4Gate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 与 `ItemEnumConsistencyTest` 一一对应：夹具/期望完全相同，只把连接换成容器里的真 MySQL。
@ExtendWith(MySqlL4Gate.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlItemEnumConsistencyIntegrationTest extends EnumConsistencyTest<ItemEntity> {

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
