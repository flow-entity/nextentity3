package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.LockMode;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.entity.OrderStatus;
import io.github.nextentity.jpa.entity.ShopOrder;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 锁模式穿过 `Collector` 的每个终止符（2.2.3 的 `.lock(PESSIMISTIC_READ).first()` 等调用点）：
/// 锁必须在终止符真正执行的查询上生效，而不是只能在 `list()` 上。
class JpaLockTerminalsTest extends JpaTestSupport {

    @Test
    @DisplayName("PESSIMISTIC_READ：first / single / list / list(n) / list(offset,limit) 都带锁")
    void readLockPassesThroughEveryTerminal() {
        ShopOrder first = inTx(entityManager ->
                orders(entityManager).query().lock(LockMode.PESSIMISTIC_READ).first());
        assertThat(first.getId()).isEqualTo(100L);
        assertThat(singleStatement()).contains("for update");

        ShopOrder single = lastStatementOf(entityManager -> orders(entityManager)
                .query()
                .where(ShopOrder::getId)
                .eq(100L)
                .lock(LockMode.PESSIMISTIC_READ)
                .single());
        assertThat(single.getId()).isEqualTo(100L);
        assertThat(singleStatement()).contains("for update");

        List<ShopOrder> all = lastStatementOf(entityManager ->
                orders(entityManager).query().lock(LockMode.PESSIMISTIC_READ).list());
        assertThat(all).extracting(ShopOrder::getId).containsExactly(100L, 101L);
        assertThat(singleStatement()).contains("for update");

        List<ShopOrder> limited = lastStatementOf(entityManager ->
                orders(entityManager).query().lock(LockMode.PESSIMISTIC_READ).list(1));
        assertThat(limited).hasSize(1);
        assertThat(singleStatement()).contains("for update");

        List<ShopOrder> paged = lastStatementOf(entityManager ->
                orders(entityManager).query().lock(LockMode.PESSIMISTIC_READ).list(1, 1));
        assertThat(paged).extracting(ShopOrder::getId).containsExactly(101L);
        assertThat(singleStatement()).contains("for update");
    }

    @Test
    @DisplayName("PESSIMISTIC_WRITE：where + orderBy + 窗口之后仍然带锁")
    void writeLockSurvivesWhereAndWindow() {
        ShopOrder locked = lastStatementOf(entityManager -> orders(entityManager)
                .query()
                .where(ShopOrder::getStatus)
                .eq(OrderStatus.PAID)
                .orderBy(ShopOrder::getId)
                .asc()
                .lock(LockMode.PESSIMISTIC_WRITE)
                .first());

        assertThat(locked.getId()).isEqualTo(100L);
        assertThat(singleStatement()).contains("for update");
    }

    private <R> R lastStatementOf(Function<EntityManager, R> work) {
        drainSql();
        R result = inTx(work);
        return result;
    }

    private String singleStatement() {
        List<String> statements = normalized(drainSql());
        assertThat(statements).as("本条用例应当只发一条语句").hasSize(1);
        return statements.getFirst();
    }

    private EntityOperations<ShopOrder> orders(EntityManager entityManager) {
        return new JpaEntityOperationsFactory(entityManager, entityManager.getEntityManagerFactory())
                .operations(ShopOrder.class);
    }
}
