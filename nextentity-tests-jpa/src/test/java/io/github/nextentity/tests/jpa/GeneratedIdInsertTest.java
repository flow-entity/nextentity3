package io.github.nextentity.tests.jpa;

import io.github.nextentity.tests.jpa.support.WriteRoundTripSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;

/// 自增主键写侧契约的 H2 通道：断言全部继承自 [GeneratedIdInsertContract]，本类只接库。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeneratedIdInsertTest extends GeneratedIdInsertContract {

    private final WriteRoundTripSupport<GeneratedItemEntity> support =
            WriteRoundTripSupport.onH2("generated_id_insert", GeneratedItemEntity.class);

    @Override
    protected WriteRoundTripSupport<GeneratedItemEntity> support() {
        return support;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
