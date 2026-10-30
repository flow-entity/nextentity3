package io.github.nextentity.tests.jdbc.extended;

import io.github.nextentity.tests.jdbc.support.L4Gate;
import org.junit.jupiter.api.extension.ExtendWith;

/// 与 [ExtendedCapabilitiesE2ETest] 同一批用例跑真 SQL Server：检验 `bit`/`float`/`real`/`smallint`/
/// `decimal(18,2)`/`datetime2(3)`/`time` 这些方言列型下的读写，以及 `@Embedded`/`@Convert`/生成键回填
/// （H2 通过不代表 SQL Server 通过）。
@ExtendWith(L4Gate.class)
class SqlServerExtendedCapabilitiesIntegrationTest extends ExtendedCapabilitiesE2ETest {

    @Override
    protected ExtendedSupport support() {
        return new SqlServerExtendedSupport();
    }
}
