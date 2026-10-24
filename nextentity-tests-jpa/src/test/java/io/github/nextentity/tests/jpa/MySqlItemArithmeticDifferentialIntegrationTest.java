package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ArithmeticDifferentialTest;
import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.tests.jpa.support.MySqlItemSupport;
import io.github.nextentity.tests.jpa.support.MySqlL4Gate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 把算术矩阵的**差分**用例接到真实 MySQL 上：L4 的载体，用例本体仍是 `nextentity-testing` 的基类。
///
/// 夹具、内存期望值、装配路径都与 H2 侧（`ItemArithmeticDifferentialTest`）完全相同，差别只有连接：
/// 同一条内存算术（Java 的 `/` 与 `%`：向零截断、余数跟随被除数）同时对上两种方言，就是跨方言对拍的证据。
///
/// 为什么要双通道：链式算术里的**整数截断**正是方言最容易分叉的地方——`(score / 3) * 3` 在
/// 向零截断下是 `9 / 18 / 30 / null / 3 / -3`，而若某方言把 `/` 当实数除法（MySQL 的 `/`
/// 对两个整数就返回 DECIMAL），这一格会立刻红。这种差异只有真库能暴露，内存 oracle 猜不出来。
@ExtendWith(MySqlL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlItemArithmeticDifferentialIntegrationTest extends ArithmeticDifferentialTest<ItemEntity> {

    private final MySqlItemSupport support = new MySqlItemSupport();

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
