package io.github.nextentity.jdbc.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.LockMode;
import io.github.nextentity.api.Path;
import io.github.nextentity.core.builder.QueryBuilder;
import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.jdbc.dialect.H2Dialect;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.SnapshotItem;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 回归：`groupBy(...)` / `having(...)` 之后 `lock(...)` 必须在**渲染之前**被拒绝。
///
/// 缺陷形态（修前）：`SqlClauses#lock` 只看 `selection instanceof EntitySelection`，于是
/// `query.groupBy(Item::status).lock(PESSIMISTIC_WRITE).list()` 渲染出
/// `select … from item t0 group by t0.status for update`——H2 / PostgreSQL 报
/// `FOR UPDATE is not allowed in DISTINCT or grouped select`，MySQL 接受同一句，同一调用一方炸
/// 一方静默持锁。契约是 `docs/DESIGN.md` §7.1 CUT-20 与 `docs/USAGE.md` §9。
///
/// 判定落在 core 的 `QueryBuilder`（`lock()` 看不到分组、`groupBy()` 看不到锁，只有组装 `QuerySpec`
/// 的地方同时看得到两者），所以本类断言的是**公开 API 的完整链路**：`list()` 在把规格交给编译器
/// 之前就抛 UOE，`executor` 一次都不被调用（没有语句被下发）。
class LockOnAggregatedQueryTest {

    private final JdbcMeta meta = new JdbcMeta();
    private final JdbcCompiler compiler = new JdbcCompiler(meta, new H2Dialect());
    private final CapturingExecutor executor = new CapturingExecutor();

    private QueryBuilder<SnapshotItem, JdbcQuery> items() {
        return new QueryBuilder<>(meta.entityType(SnapshotItem.class), compiler, executor);
    }

    @Test
    @DisplayName("groupBy(...) 之后 lock(...)：list() 抛 UOE，且一条语句都没渲染")
    void groupByThenLockIsRejectedBeforeRendering() {
        assertThatThrownBy(() -> items().groupBy(SnapshotItem::status)
                        .lock(LockMode.PESSIMISTIC_WRITE)
                        .list())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("锁只支持实体查询: " + SnapshotItem.class.getName());

        assertThat(executor.last).as("拒绝必须发生在渲染之前，不能先渲染出 group by + for update").isNull();
    }

    @Test
    @DisplayName("having(...) 之后 lock(...)：list() 同样抛 UOE，且一条语句都没渲染")
    void havingThenLockIsRejectedBeforeRendering() {
        assertThatThrownBy(
                        () -> items().having(Path.of(SnapshotItem::id).count().gt(0L))
                                .lock(LockMode.PESSIMISTIC_READ)
                                .list())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("锁只支持实体查询: " + SnapshotItem.class.getName());

        assertThat(executor.last).isNull();
    }

    @Test
    @DisplayName("反向约束：同一条链上的 count() / exists() 不受影响（锁已被 forCounting / forExistence 清掉）")
    void countAndExistsOnAggregatedLockedQueryStillWork() {
        long groups = items().groupBy(SnapshotItem::status)
                .lock(LockMode.PESSIMISTIC_WRITE)
                .count();
        boolean any = items().groupBy(SnapshotItem::status)
                .lock(LockMode.PESSIMISTIC_WRITE)
                .exists();

        assertThat(groups).isEqualTo(100L);
        assertThat(any).isFalse();
        assertThat(executor.last.spec().lock()).isNull();
        assertThat(executor.last.sql()).doesNotContain("for update");
    }

    @Test
    @DisplayName("正向对照：实体查询上的锁照常渲染 for update（不是把锁一律禁掉）")
    void plainEntityQueryStillRendersForUpdate() {
        items().where(Path.of(SnapshotItem::score))
                .gt(10)
                .lock(LockMode.PESSIMISTIC_WRITE)
                .list();

        assertThat(executor.last.sql()).endsWith("where t0.score > ? for update");
    }

    /// 捕获式执行器：只记录编译产物，不连库。
    private static final class CapturingExecutor implements Executor<JdbcQuery> {

        private JdbcQuery last;

        @Override
        public <R> List<R> list(JdbcQuery query) {
            last = query;
            return List.of();
        }

        @Override
        public long count(JdbcQuery query) {
            last = query;
            return 100L;
        }

        @Override
        public boolean exists(JdbcQuery query) {
            last = query;
            return false;
        }
    }
}
