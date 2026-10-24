package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.LockMode;
import io.github.nextentity.testing.ItemStatus;
import io.github.nextentity.tests.jpa.support.MySqlItemSupport;
import io.github.nextentity.tests.jpa.support.MySqlL4Gate;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 悲观锁在真实 MySQL 上的形态：事务内读到行，且语句必须带 `FOR UPDATE`。
///
/// 与 H2 侧 `ItemLockConsistencyTest` 同一场景、同一夹具、同一断言；本类只换方言。
/// 上真库的意义在 MySQL 侧：`FOR UPDATE` 是行锁语义的入口，H2 兼容模式接受这条语法，
/// 但只有真 MySQL 能证明它在事务内真的下发了。
@ExtendWith(MySqlL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlItemLockIntegrationTest {

    private final MySqlItemSupport support = new MySqlItemSupport();

    @BeforeEach
    void seed() {
        support.seedData(List.of(support.newItem(1L, "apple", 10, 10, null, ItemStatus.NEW)));
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }

    @Test
    void pessimisticWriteLockReadsRowAndEmitsForUpdate() {
        support.clearSql();

        List<ItemEntity> rows = support.inTransaction(() -> support.query()
                .where(support.idRef())
                .eq(1L)
                .lock(LockMode.PESSIMISTIC_WRITE)
                .list());

        assertThat(rows).extracting(ItemEntity::id).containsExactly(1L);
        // 断言锁子句**紧跟在本次的 where 条件之后、落在语句末尾**，不是「语句里某处出现过 for update」：
        // 片段断言对「锁加在别的表/子查询上」「主查询没加锁但子查询带了 for update」都可能假绿。
        // 不用整句 `isEqualTo` 是因为 MySQL 会在锁子句后追加 `of <别名>`（H2 不追加），
        // 所以只把这一段写成可选后缀，其余部分逐字符钉住。
        List<String> sqls = normalized(support.drainSql());
        assertThat(sqls).hasSize(1);
        assertThat(sqls.getFirst())
                .matches("select ie1_0\\.id,ie1_0\\.name,ie1_0\\.note,ie1_0\\.score,ie1_0\\.score2,ie1_0\\.status"
                        + " from item ie1_0 where ie1_0\\.id=\\? for update( of ie1_0)?");
    }

    /// 与 `ItemSqlSnapshotTest` 同一套归一化：转小写 + 连续空白压一个空格，其余字符原样比对。
    private static List<String> normalized(List<String> sqls) {
        return sqls.stream()
                .map(sql -> sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim())
                .toList();
    }

    @Test
    void pessimisticWriteLockOnMissingRowReturnsEmpty() {
        List<ItemEntity> rows = support.inTransaction(() -> support.query()
                .where(support.idRef())
                .eq(999L)
                .lock(LockMode.PESSIMISTIC_WRITE)
                .list());

        assertThat(rows).isEmpty();
    }
}
