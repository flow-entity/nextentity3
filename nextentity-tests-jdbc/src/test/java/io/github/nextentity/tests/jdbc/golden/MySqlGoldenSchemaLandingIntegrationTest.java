package io.github.nextentity.tests.jdbc.golden;

import io.github.nextentity.tests.jdbc.support.MySqlL4Gate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// golden schema 的 MySQL 容器通道（failsafe）：与 H2 版跑同一批用例，只换 `schema-mysql.sql` 的数据源。
@ExtendWith(MySqlL4Gate.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlGoldenSchemaLandingIntegrationTest extends GoldenSchemaLandingContract {

    private final GoldenJdbcSupport support = new MySqlGoldenSupport();

    @Override
    protected GoldenJdbcSupport support() {
        return support;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
