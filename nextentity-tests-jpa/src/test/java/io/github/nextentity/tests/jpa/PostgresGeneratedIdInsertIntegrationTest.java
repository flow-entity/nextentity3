package io.github.nextentity.tests.jpa;

import io.github.nextentity.tests.jpa.support.PostgresL4Gate;
import io.github.nextentity.tests.jpa.support.WriteRoundTripSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 自增主键写侧契约的真方言版。
///
/// 真 PostgreSQL 上多出的风险正是这条通道存在的理由：`AUTO_INCREMENT` 单调前进、`delete from` 之后
/// **不**回收已发出的号，所以 `insertAfterDeleteDoesNotReuseTheDeletedId` 这条只有在真库上跑过，
/// 才证明框架没有把上一次取到的号复用（H2 的行为随版本变，不能当基准）；反过来，若哪天实现改成
/// 「先查 max(id)+1 再写」，PostgreSQL 的并发与回滚语义会让它在这里露出来。
/// 其余用例两边跑同一套断言——契约基类只写一遍，子类只换接线。
@ExtendWith(PostgresL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresGeneratedIdInsertIntegrationTest extends GeneratedIdInsertContract {

    private final WriteRoundTripSupport<GeneratedItemEntity> support =
            WriteRoundTripSupport.onPostgres(GeneratedItemEntity.class);

    @Override
    protected WriteRoundTripSupport<GeneratedItemEntity> support() {
        return support;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
