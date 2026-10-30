package io.github.nextentity.tests.jpa.golden;

import io.github.nextentity.tests.jpa.support.MySqlL4Gate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// MySQL 容器通道：golden schema 的 JPA 落地契约在真 MySQL 上重跑一遍（与 H2 同一批用例）。
@ExtendWith(MySqlL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlGoldenSchemaLandingIntegrationTest extends JpaGoldenSchemaLandingContract {

    private final JpaGoldenSupport support = JpaGoldenSupport.onMySql();

    @Override
    protected JpaGoldenSupport support() {
        return support;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
