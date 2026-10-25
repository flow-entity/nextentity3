package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.NullOrdering;
import io.github.nextentity.tests.jpa.support.HibernateHarness;
import io.github.nextentity.tests.jpa.support.WriteRoundTripSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;

/// 时间类型契约的 H2 通道：断言全部继承自 [TemporalWriteContract]。
///
/// `nullOrdering()` 走 [HibernateHarness#nullOrdering()]：H2 的兼容模式是可切换的
/// （`-Dnextentity.h2mode=postgresql`），把口径钉在装置上，opt-in 通道才不需要改用例。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TemporalWriteTest extends TemporalWriteContract {

    private final WriteRoundTripSupport<TemporalItemEntity> support =
            WriteRoundTripSupport.onH2("temporal_write", TemporalItemEntity.class);

    @Override
    protected WriteRoundTripSupport<TemporalItemEntity> support() {
        return support;
    }

    @Override
    protected NullOrdering nullOrdering() {
        return HibernateHarness.nullOrdering();
    }

    /// 同一套装置口径：H2 的 PG 兼容模式把 `Instant` 绑成丢偏移的 `timestamp`，其余模式保时刻。
    @Override
    protected boolean instantRoundTripKeepsTheInstant() {
        return HibernateHarness.resolveMode(System.getProperty(HibernateHarness.MODE_PROPERTY))
                != HibernateHarness.H2Mode.POSTGRESQL;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
