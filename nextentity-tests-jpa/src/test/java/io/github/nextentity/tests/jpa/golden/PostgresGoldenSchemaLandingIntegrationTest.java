package io.github.nextentity.tests.jpa.golden;

import io.github.nextentity.tests.jpa.support.PostgresL4Gate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// PostgreSQL 容器通道：golden schema 的 JPA 落地契约在真 PostgreSQL 上重跑一遍
/// （与 MySQL/H2 同一批用例，凭证据此做方言矩阵条数等式）。
@ExtendWith(PostgresL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresGoldenSchemaLandingIntegrationTest extends JpaGoldenSchemaLandingContract {

    private final JpaGoldenSupport support = JpaGoldenSupport.onPostgres();

    @Override
    protected JpaGoldenSupport support() {
        return support;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
