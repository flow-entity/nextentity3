package io.github.nextentity.tests.jpa;

import io.github.nextentity.tests.jpa.support.MySqlL4Gate;
import io.github.nextentity.tests.jpa.support.WriteRoundTripSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 自增主键写侧契约的真方言版。
///
/// 真 MySQL 上多出的风险正是这条通道存在的理由：`AUTO_INCREMENT` 在 `delete from` 之后
/// **不**重置（H2 会），所以「第二条号更大」这类断言只有在真库上跑过，才证明框架没有把
/// 上一次取到的号复用；反过来，若哪天实现改成「先查 max(id)+1 再写」，MySQL 的并发与回滚语义
/// 会让它在这里露出来。
@ExtendWith(MySqlL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlGeneratedIdInsertIntegrationTest extends GeneratedIdInsertContract {

    private final WriteRoundTripSupport<GeneratedItemEntity> support =
            WriteRoundTripSupport.onMySql(GeneratedItemEntity.class);

    @Override
    protected WriteRoundTripSupport<GeneratedItemEntity> support() {
        return support;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
