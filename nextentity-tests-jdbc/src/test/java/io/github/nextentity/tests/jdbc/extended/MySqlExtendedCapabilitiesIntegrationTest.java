package io.github.nextentity.tests.jdbc.extended;

import io.github.nextentity.tests.jdbc.support.MySqlL4Gate;
import org.junit.jupiter.api.extension.ExtendWith;

/// 与 [ExtendedCapabilitiesE2ETest] 同一批用例跑真 MySQL：检验 tinyint(1)/float/smallint/datetime(3)
/// 这些方言列型下的读写与 `@Convert`/`@Embedded`/生成键回填（H2 通过不代表 MySQL 通过）。
@ExtendWith(MySqlL4Gate.class)
class MySqlExtendedCapabilitiesIntegrationTest extends ExtendedCapabilitiesE2ETest {

    @Override
    protected ExtendedSupport support() {
        return new MySqlExtendedSupport();
    }
}
