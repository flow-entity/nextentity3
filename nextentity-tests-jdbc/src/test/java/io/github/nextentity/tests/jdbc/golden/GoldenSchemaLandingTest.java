package io.github.nextentity.tests.jdbc.golden;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;

/// golden schema 的 H2 通道（surefire）：独立内存库 + `schema-h2.sql` 的 golden 段。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoldenSchemaLandingTest extends GoldenSchemaLandingContract {

    private final GoldenJdbcSupport support = new GoldenJdbcSupport("golden_schema_landing");

    @Override
    protected GoldenJdbcSupport support() {
        return support;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
