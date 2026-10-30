package io.github.nextentity.tests.jdbc.golden;

import io.github.nextentity.tests.jdbc.support.L4Gate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// golden schema 的 SQL Server 容器通道（failsafe）：与 H2 版跑同一批用例，只换 `schema-sqlserver.sql`。
@ExtendWith(L4Gate.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlServerGoldenSchemaLandingIntegrationTest extends GoldenSchemaLandingContract {

    private final GoldenJdbcSupport support = new SqlServerGoldenSupport();

    @Override
    protected GoldenJdbcSupport support() {
        return support;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
