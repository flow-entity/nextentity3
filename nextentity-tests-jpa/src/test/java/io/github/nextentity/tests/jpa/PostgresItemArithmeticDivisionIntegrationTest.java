package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.Path;
import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.ConsistencyTestBase;
import io.github.nextentity.testing.FixtureRow;
import io.github.nextentity.testing.Fixtures;
import io.github.nextentity.tests.jpa.support.PostgresItemSupport;
import io.github.nextentity.tests.jpa.support.PostgresL4Gate;
import java.util.List;
import org.hibernate.exception.DataException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 除零与整除在 **PostgreSQL** 上的行为：整数除法先截断，除零抛 `DataException`。
///
/// PostgreSQL 与 H2 在整数除法上一致（`integer / integer` 向零截断），与 MySQL 不同
/// （MySQL 的 `/` 走 DECIMAL 除法，`10 / 3 = 3.3333`）——所以本类在**两条断言上都与 H2 侧
/// `ItemArithmeticDivisionTest` 同口径**，而不是 `MySqlItemArithmeticDivisionIntegrationTest`
/// 那套「除零返回 null、除法不截断」的 MySQL 语义。把 MySQL 的期望抄过来会让这条真库通道
/// 用错方言事实，比没有通道更糟：它会把「PG 不支持某能力」这种假结论写进台账。
///
/// 为什么必须单独一个类：`ArithmeticDifferentialTest` 的矩阵是**后端中立**的，除零与整除
/// 恰好是方言分叉点，写进中立基类就只能挑一侧当通用语义。两侧各钉一侧，差异才看得见。
///
/// 断言口径：除零用 `assertThatThrownBy(…).isInstanceOf(DataException.class)`，只钉到
/// Hibernate 的异常类型、不钉 PG 的文案（文案随版本变，钉住它只会制造假红）；整除的期望值
/// 由内存侧的整数除法独立算出（Java 的 `/` 向零截断），不读后端输出。
@ExtendWith(PostgresL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresItemArithmeticDivisionIntegrationTest extends ConsistencyTestBase<ItemEntity> {

    private final PostgresItemSupport support = new PostgresItemSupport();

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

    /// 自检：夹具里必须有多行非 `null` 的 `score`，否则「抛异常」可能只是 `null` 行被跳过。
    @Test
    void fixtureHasNonNullScoresSoTheDivisionIsNotVacuous() {
        assertThat(fixtureRows().stream().filter(row -> row.score() != null).count())
                .isGreaterThan(1L);
    }

    /// `(score / 3) * 3` 在 PG 上**先截断再乘**：`10 → 3 → 9`，与 H2 一致。
    ///
    /// 期望值按 Java 的整数除法独立建模，不抄后端结果。
    @Test
    void divThenMulTruncatesBeforeMultiplying() {
        List<Integer> actual = query().select(Path.of(scoreRef()).div(3).mul(3))
                .orderBy(idRef())
                .asc()
                .list();
        List<Integer> expected = fixtureRows().stream()
                .map(row -> row.score() == null ? null : row.score() / 3 * 3)
                .toList();

        assertThat(actual).containsExactlyElementsOf(expected);
    }

    @Test
    void divByZeroFailsInsteadOfReturningNull() {
        assertThatThrownBy(() -> query().select(Path.of(scoreRef()).div(0))
                        .orderBy(idRef())
                        .asc()
                        .list())
                .isInstanceOf(DataException.class);
    }

    @Test
    void modByZeroFailsInsteadOfReturningNull() {
        assertThatThrownBy(() -> query().select(Path.of(scoreRef()).mod(0))
                        .orderBy(idRef())
                        .asc()
                        .list())
                .isInstanceOf(DataException.class);
    }
}
