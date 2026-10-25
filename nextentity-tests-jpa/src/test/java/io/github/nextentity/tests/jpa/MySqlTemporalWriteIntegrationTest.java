package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.NullOrdering;
import io.github.nextentity.tests.jpa.support.MySqlL4Gate;
import io.github.nextentity.tests.jpa.support.WriteRoundTripSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 时间类型契约的真方言版：真 MySQL 上时间列的精度与类型（`datetime(6)` / `date`）才是真的，
/// H2 的 MySQL 兼容模式只保证语法像。
///
/// `Instant` 尤其需要在真库上跑：Hibernate 对 `Instant` 的绑定要经过 JDBC 的时区换算，
/// H2 与 MySQL 的驱动在这条路径上的行为并不共享实现，只有真库执行过才算数。
@ExtendWith(MySqlL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlTemporalWriteIntegrationTest extends TemporalWriteContract {

    private final WriteRoundTripSupport<TemporalItemEntity> support =
            WriteRoundTripSupport.onMySql(TemporalItemEntity.class);

    @Override
    protected WriteRoundTripSupport<TemporalItemEntity> support() {
        return support;
    }

    @Override
    protected NullOrdering nullOrdering() {
        // 真 MySQL 的 ASC 把 NULL 排在最前
        return NullOrdering.NULLS_FIRST;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
