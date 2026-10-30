package io.github.nextentity.tests.jdbc.extended;

import io.github.nextentity.tests.jdbc.support.L4Gate;
import org.junit.jupiter.api.extension.ExtendWith;

/// 与 [ExtendedCapabilitiesE2ETest] 同一批用例跑真 PostgreSQL：检验 boolean/double precision/real/
/// smallint/numeric(18,2)/timestamp(3)/time 这些方言列型下的读写与 `@Convert`/`@Embedded`/生成键回填
/// （方言矩阵要求与 MySQL 通道同批用例、条数相等）。
@ExtendWith(L4Gate.class)
class PostgresExtendedCapabilitiesIntegrationTest extends ExtendedCapabilitiesE2ETest {

    @Override
    protected ExtendedSupport support() {
        return new PostgresExtendedSupport();
    }
}
