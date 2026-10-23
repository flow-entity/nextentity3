package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.Ordering;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.Window;
import io.github.nextentity.jpa.compile.JpaCompiler;
import io.github.nextentity.jpa.entity.ShopOrder;
import io.github.nextentity.jpa.exec.JpaExecutor;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `JpaExecutor.list` 的分页边界（`exec/JpaExecutor.java:41`、`:44`）。
///
/// 现有 `JpaEndToEndTest#orderingAndWindow` 只用 `Window(1, 1)`：`offset=1` 时两行里正好剩一行，
/// 「`limit` 根本没生效」与「`limit` 生效」的**结果集完全一样**，所以两条边界条件都活了下来。
/// 本类把 `offset` 与 `limit` 拆开测：
/// - `limit = 0` 按 `Window#limited()` 的语义是「不截断行数」，变异成 `>= 0` 会 `setMaxResults(0)` 返回 0 行；
/// - `offset = 0` 时必须仍然应用 `limit`（变异体把 `> 0` 判反后整个 `setMaxResults` 被跳过）；
/// - `offset = 0` 时不下发 `offset` 子句（`> 0` → `>= 0` 的唯一可观测差异）。
class JpaExecutorWindowTest extends JpaTestSupport {

    @Test
    @DisplayName("limit = 0 表示不截断：两行全返回")
    void limitZeroMeansNoLimit() {
        assertThat(ids(new Window(0, 0))).containsExactly(100L, 101L);
    }

    @Test
    @DisplayName("offset = 0 时 limit 仍然生效：只返回第一行，且不下发 offset 子句")
    void limitAppliedWhenOffsetIsZero() {
        assertThat(ids(new Window(0, 1))).containsExactly(100L);
        // `offset = 0` 时结果集与「下发 offset 0」相同，但 `> 0` 这道守卫的意义正是不发无谓子句：
        // Hibernate 7.x + H2Dialect 下 `setFirstResult(0)` 会多渲染一个 `offset ? rows`
        assertThat(drainSql()).noneMatch(sql -> sql.toLowerCase(Locale.ROOT).contains("offset"));
    }

    @Test
    @DisplayName("只有 offset：跳过第一行，不设上限")
    void offsetAppliedWithoutLimit() {
        assertThat(ids(new Window(1, null))).containsExactly(101L);
        assertThat(drainSql()).anySatisfy(sql -> assertThat(sql).containsIgnoringCase("offset"));
    }

    @Test
    @DisplayName("无窗口：既不过滤也不截断")
    void noWindowReturnsEveryRow() {
        assertThat(ids(Window.NONE)).containsExactly(100L, 101L);
    }

    private List<Long> ids(Window window) {
        QuerySpec spec = new QuerySpec(
                ShopOrder.class,
                new EntitySelection(),
                null,
                List.of(),
                null,
                List.of(new Ordering(AttributePath.of(ShopOrder.class, "id"), Ordering.Direction.ASC)),
                window,
                null);
        return inTx(entityManager ->
                executor(entityManager).<ShopOrder>list(compiler(entityManager).compile(spec)).stream()
                        .map(ShopOrder::getId)
                        .toList());
    }

    private JpaCompiler compiler(EntityManager entityManager) {
        return new JpaCompiler(entityManager, meta());
    }

    private JpaExecutor executor(EntityManager entityManager) {
        return new JpaExecutor(entityManager, meta());
    }
}
