package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.nextentity.api.Path;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.entity.OrderStatus;
import io.github.nextentity.jpa.entity.ShopOrder;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// 小数金额在聚合里的精度语义：`SUM` 必须精确，`AVG` 按契约落 `Double`。
///
/// 夹具专挑**二进制浮点会露怯**的数：`0.10 + 0.20 + 0.40` 的 double 和是 `0.7000000000000001`。
/// 如果后端把 `BigDecimal` 列当 `double` 求和（或少渲染了 `SUM(列)` 而退化成别的东西），
/// 这里立刻红；`Integer` 夹具（`nextentity-testing` 的 `Item`）永远碰不到这一层。
///
/// oracle 用差分：期望值由同一批行的 JVM 端 `reduce` 算出，不写死「一条 SQL 该得 0.70」——
/// 常量只钉「语义本该如此」的那一条（`0.70` 与 double 反例），正确性证据来自两条独立路径对拍。
class JpaDecimalAggregateTest extends JpaTestSupport {

    /// 三行有效金额 + 一行金额为 `null`：`null` 行既不进 `SUM`，也不进 `COUNT(列)`。
    @BeforeEach
    void seedDecimalAmounts() {
        inTx(entityManager -> {
            entityManager.createQuery("delete from ShopOrder").executeUpdate();
            entityManager.persist(new ShopOrder(200L, null, OrderStatus.PAID, new BigDecimal("0.10"), null));
            entityManager.persist(new ShopOrder(201L, null, OrderStatus.PAID, new BigDecimal("0.20"), null));
            entityManager.persist(new ShopOrder(202L, null, OrderStatus.PAID, new BigDecimal("0.40"), null));
            entityManager.persist(new ShopOrder(203L, null, OrderStatus.PAID, null, null));
            return null;
        });
        drainSql();
    }

    /// `SUM(小数列)` 精确等于逐行相加，不是二进制浮点近似。
    @Test
    void sumOfDecimalColumnIsExact() {
        inTx(entityManager -> {
            List<BigDecimal> rows = amounts(entityManager);
            BigDecimal expected = rows.stream().reduce(BigDecimal.ZERO, BigDecimal::add);

            List<BigDecimal> actual = operations(entityManager)
                    .query()
                    .select(Path.of(ShopOrder::getAmount).sum())
                    .list();

            assertThat(actual).hasSize(1);
            assertThat(actual.get(0)).isEqualByComparingTo(expected);
            // 夹具语义：三行之和就是 0.70；double 相加得不到它，所以上面那条不是同义反复。
            assertThat(expected).isEqualByComparingTo("0.70");
            assertThat(0.10 + 0.20 + 0.40).isNotEqualTo(0.70);
            return null;
        });
    }

    /// `AVG(小数列)` 的返回类型是 `Double`（见 `NumberPath#avg`），值等于 JVM 端的平均值。
    @Test
    void avgOfDecimalColumnIsDoubleAndMatchesInMemoryAverage() {
        inTx(entityManager -> {
            List<BigDecimal> rows = amounts(entityManager);
            double expected =
                    rows.stream().mapToDouble(BigDecimal::doubleValue).average().orElseThrow();

            List<Double> actual = operations(entityManager)
                    .query()
                    .select(Path.of(ShopOrder::getAmount).avg())
                    .list();

            assertThat(actual).hasSize(1);
            assertThat(actual.get(0)).isCloseTo(expected, within(1e-12));
            assertThat(actual.get(0)).isCloseTo(0.7 / 3, within(1e-12));
            return null;
        });
    }

    /// `MIN` / `MAX` 保持小数值与标度，不被折算成浮点。
    @Test
    void minMaxOfDecimalColumnKeepExactValues() {
        inTx(entityManager -> {
            List<BigDecimal> rows = amounts(entityManager);
            BigDecimal expectedMin =
                    rows.stream().min(Comparator.naturalOrder()).orElseThrow();
            BigDecimal expectedMax =
                    rows.stream().max(Comparator.naturalOrder()).orElseThrow();

            List<BigDecimal> min = operations(entityManager)
                    .query()
                    .select(Path.of(ShopOrder::getAmount).min())
                    .list();
            List<BigDecimal> max = operations(entityManager)
                    .query()
                    .select(Path.of(ShopOrder::getAmount).max())
                    .list();

            assertThat(min.get(0)).isEqualByComparingTo(expectedMin);
            assertThat(max.get(0)).isEqualByComparingTo(expectedMax);
            assertThat(expectedMin).isEqualByComparingTo("0.10");
            assertThat(expectedMax).isEqualByComparingTo("0.40");
            return null;
        });
    }

    /// `COUNT(列)` 只数非 `null` 行，`COUNT(*)` 数全部行——两者在这批夹具上差 1。
    @Test
    void countOfColumnSkipsNullsUnlikeCountOfId() {
        inTx(entityManager -> {
            long countOfAmount = operations(entityManager)
                    .query()
                    .select(Path.of(ShopOrder::getAmount).count())
                    .list()
                    .get(0);
            long countDistinctOfAmount = operations(entityManager)
                    .query()
                    .select(Path.of(ShopOrder::getAmount).countDistinct())
                    .list()
                    .get(0);
            long countOfId = operations(entityManager)
                    .query()
                    .select(Path.of(ShopOrder::getId).count())
                    .list()
                    .get(0);

            assertThat(countOfAmount).isEqualTo(3L);
            assertThat(countDistinctOfAmount).isEqualTo(3L);
            assertThat(countOfId).isEqualTo(4L);
            return null;
        });
    }

    /// 有行但整列都是 `null`：`SUM`/`MIN`/`MAX` 是 `null`，`COUNT(列)` 是 `0`——
    /// 这既不是「无行」也不是「和为 0」。
    @Test
    void allNullColumnAggregatesFollowTheirOwnContract() {
        inTx(entityManager -> {
            entityManager.createQuery("delete from ShopOrder").executeUpdate();
            entityManager.persist(new ShopOrder(210L, null, OrderStatus.PAID, null, null));
            return null;
        });

        inTx(entityManager -> {
            assertThat(operations(entityManager)
                            .query()
                            .select(Path.of(ShopOrder::getAmount).sum())
                            .list())
                    .containsExactly((BigDecimal) null);
            assertThat(operations(entityManager)
                            .query()
                            .select(Path.of(ShopOrder::getAmount).max())
                            .list())
                    .containsExactly((BigDecimal) null);
            assertThat(operations(entityManager)
                            .query()
                            .select(Path.of(ShopOrder::getAmount).count())
                            .list())
                    .containsExactly(0L);
            assertThat(operations(entityManager)
                            .query()
                            .select(Path.of(ShopOrder::getId).count())
                            .list())
                    .containsExactly(1L);
            return null;
        });
    }

    /// 非 `null` 的金额列，按主键升序——给差分计算当输入。
    private static List<BigDecimal> amounts(EntityManager entityManager) {
        return entityManager
                .createQuery(
                        "select o.amount from ShopOrder o where o.amount is not null order by o.id", BigDecimal.class)
                .getResultList();
    }

    private static EntityOperations<ShopOrder> operations(EntityManager entityManager) {
        return new JpaEntityOperationsFactory(entityManager, entityManager.getEntityManagerFactory())
                .operations(ShopOrder.class);
    }
}
