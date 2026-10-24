package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.Path;
import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.ConsistencyTestBase;
import io.github.nextentity.testing.FixtureRow;
import io.github.nextentity.testing.Fixtures;
import java.util.List;
import org.hibernate.exception.DataException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// 除零在 **H2** 上的行为：抛 `DataException`，不是返回 `NULL`。
///
/// 这是跨方言对拍的另一半。`ArithmeticDifferentialTest` 的矩阵刻意不含除数为 0 的格子
/// （两个后端行为不同，写进后端中立的基类会绑死方言），改由两条按方言分开的用例各钉一半：
/// - H2：本类，`score / 0` 与 `score % 0` 都抛 `org.hibernate.exception.DataException`；
/// - MySQL：`MySqlItemArithmeticDivisionIntegrationTest`，同样两个表达式逐值返回 `null`。
///
/// 两边合起来才把「除零行为随方言变」这件事钉住：只钉一侧，另一侧就可能在升级后被悄悄改写
/// 而无人发现。异常类型只钉到 Hibernate 的 `DataException`，不钉 H2 的文案
/// （文案随版本变，钉住它只会制造假红）。
///
/// 夹具用 `Fixtures.OPERATOR_ROWS`（`score` = 10 / 20 / 30 / `null` / 5 / -4）：其中既有非 `null` 行，
/// 也有 `null` 行，因此「抛异常」不可能是 `null` 行被跳过造成的。
class ItemArithmeticDivisionTest extends ConsistencyTestBase<ItemEntity> {

    private final JpaItemSupport support = new JpaItemSupport("item_arithmetic_division");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @Override
    protected List<FixtureRow> fixtureRows() {
        return Fixtures.OPERATOR_ROWS;
    }

    @AfterEach
    void closeSupport() {
        support.close();
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
