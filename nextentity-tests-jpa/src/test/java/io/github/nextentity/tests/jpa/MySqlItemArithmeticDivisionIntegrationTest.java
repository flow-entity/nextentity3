package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.ConsistencyTestBase;
import io.github.nextentity.testing.FixtureRow;
import io.github.nextentity.testing.Fixtures;
import io.github.nextentity.tests.jpa.support.MySqlItemSupport;
import io.github.nextentity.tests.jpa.support.MySqlL4Gate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 除零在 **MySQL** 上的行为：`score / 0` 与 `score % 0` 逐值返回 `null`，不抛异常。
///
/// 这是 `ItemArithmeticDivisionTest`（H2 侧，抛 `DataException`）的对拍另一半：
/// 同一条表达式、同一个夹具、同一套装配路径，只有连接不同，行为差异被两侧各自钉死。
///
/// 为什么必须单独一个类：`ArithmeticDifferentialTest` 的矩阵是**后端中立**的，
/// 而除零恰好是两方言不一致的少数点之一，写进去就会把某一侧的行为当成通用语义。
/// 文档里长期写着「除零属于 L4 的职责」，却一直没有用例真做——本类与 H2 侧补上这个缺口。
///
/// 断言口径：整个投影列 `containsOnlyNulls()`。夹具里 id=1/2/3/5/6 的 `score` 都非 `null`，
/// 所以「全 `null`」只能来自除零本身，不可能是 `null` 行被跳过造成的；
/// 另加一条自检确认夹具确实含非 `null` 行，避免夹具将来被换成全 `null` 后本类变成恒真。
@ExtendWith(MySqlL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlItemArithmeticDivisionIntegrationTest extends ConsistencyTestBase<ItemEntity> {

    private final MySqlItemSupport support = new MySqlItemSupport();

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @Override
    protected List<FixtureRow> fixtureRows() {
        return Fixtures.OPERATOR_ROWS;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }

    @Test
    void fixtureHasNonNullScoresSoNullsCannotBeVacuous() {
        assertThat(fixtureRows().stream().filter(row -> row.score() != null).count())
                .isGreaterThan(1L);
    }

    /// `(score / 3) * 3` 在 MySQL 上**不截断**：逐值还原成 `10 / 20 / 30 / null / 5 / -4`。
    ///
    /// 与 H2 侧 `ItemArithmeticDivisionTest.divThenMulTruncatesBeforeMultiplying`（`9 / 18 / 30 / null / 3 / -3`）
    /// 构成同一表达式的跨方言对拍：MySQL 的 `/` 对两个整数返回 DECIMAL（`10/3 = 3.3333`），
    /// 乘回 3 后由投影类型转回 `Integer` 时取整，因此不丢精度；H2 的 `INTEGER / INTEGER`
    /// 是整数除法，先截断再乘。**这是真实存在的方言差异，不是实现缺陷**，所以两侧各钉一侧，
    /// 谁都不许把对方的结果当成通用语义。
    ///
    /// 期望值按「十进制除法 + 取整回 Integer」独立建模，不抄后端输出。
    @Test
    void divThenMulKeepsDecimalPrecision() {
        List<Integer> actual = query().select(Path.of(scoreRef()).div(3).mul(3))
                .orderBy(idRef())
                .asc()
                .list();
        List<Integer> expected = fixtureRows().stream()
                .map(row -> row.score() == null ? null : (int) Math.round(row.score() / 3.0 * 3))
                .toList();

        assertThat(actual).containsExactlyElementsOf(expected);
    }

    @Test
    void divByZeroYieldsNull() {
        List<Integer> actual = query().select(Path.of(scoreRef()).div(0))
                .orderBy(idRef())
                .asc()
                .list();

        assertThat(actual).hasSize(fixtureRows().size()).containsOnlyNulls();
    }

    @Test
    void modByZeroYieldsNull() {
        List<Integer> actual = query().select(Path.of(scoreRef()).mod(0))
                .orderBy(idRef())
                .asc()
                .list();

        assertThat(actual).hasSize(fixtureRows().size()).containsOnlyNulls();
    }
}
