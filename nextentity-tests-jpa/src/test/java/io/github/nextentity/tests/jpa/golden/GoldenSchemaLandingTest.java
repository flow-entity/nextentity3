package io.github.nextentity.tests.jpa.golden;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;

/// H2 通道：把 golden schema 的 JPA 落地契约接到 H2（`MODE=MySQL`）上。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoldenSchemaLandingTest extends JpaGoldenSchemaLandingContract {

    private final JpaGoldenSupport support = new JpaGoldenSupport("golden_schema_landing");

    @Override
    protected JpaGoldenSupport support() {
        return support;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
