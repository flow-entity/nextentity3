package io.github.nextentity.tests.jdbc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.LockMode;
import io.github.nextentity.testing.ItemStatus;
import io.github.nextentity.tests.jdbc.support.L4Gate;
import io.github.nextentity.tests.jdbc.support.SqlServerItemSupport;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// SQL Server 没有等价 `for update`（`with (updlock)` 在 autocommit 下不持锁），方言显式拒绝（§7.7）。
@ExtendWith(L4Gate.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlServerLockContractIntegrationTest {

    private final SqlServerItemSupport support = new SqlServerItemSupport("sqlserverlockcontract");

    @BeforeEach
    void seed() {
        support.seedData(List.of(item(1L, "a", 10)));
    }

    @Test
    void pessimisticWriteIsRejectedByDialect() {
        assertThatThrownBy(
                        () -> support.query().lock(LockMode.PESSIMISTIC_WRITE).list())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("SQL Server 不支持行锁（autocommit 下 with (updlock) 语义不等价）: PESSIMISTIC_WRITE");
    }

    @Test
    void otherLockModesAreRejectedByCompiler() {
        for (LockMode mode : List.of(
                LockMode.OPTIMISTIC,
                LockMode.OPTIMISTIC_FORCE_INCREMENT,
                LockMode.PESSIMISTIC_READ,
                LockMode.PESSIMISTIC_FORCE_INCREMENT)) {
            assertThatThrownBy(() -> support.query().lock(mode).list())
                    .as("锁模式 %s 必须拒绝", mode)
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("JDBC 后端只支持 PESSIMISTIC_WRITE: " + mode);
        }
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }

    private static ItemEntity item(long id, String name, Integer score) {
        return new ItemEntity(id, name, score, score, null, ItemStatus.ACTIVE);
    }
}
