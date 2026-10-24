package io.github.nextentity.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `Operation` / `LockMode` 两个枚举本身就是 `api` 的线格式词汇表：后端用**无 `default` 的 `switch`**
/// 按 `Operation` 穷尽分派，`LockMode` 由后端映射成自己的锁类型。
///
/// 因此这两个枚举的常量集合是契约的一部分，不是实现细节：增删一个常量就会改变线格式，
/// 后端要么编译失败、要么静默走到新分支。这里把常量名逐个钉住，让「加了常量」这件事在 `api` 侧
/// 也要过一次显式的用例改动，而不是只体现在后端的 `switch` 报错上。
///
/// 翻译证据（`Operation` → `core.spec` 节点、`LockMode` → 后端锁类型）不在本套件里：
/// 前者在 `nextentity-core` 的 `ApiOperatorAssemblyTest`，后者在 `jpa`，两层不互相代替。
@DisplayName("Operation / LockMode：线格式词汇表")
class OperationVocabularyTest {

    @Test
    @DisplayName("Operation 的常量集合与顺序就是后端的穷尽分派依据")
    void operationVocabulary() {
        assertThat(Operation.values())
                .extracting(Operation::name)
                .containsExactly(
                        "EQ",
                        "NE",
                        "GE",
                        "GT",
                        "LE",
                        "LT",
                        "IN",
                        "NOT_IN",
                        "IS_NULL",
                        "IS_NOT_NULL",
                        "BETWEEN",
                        "CONTAINS",
                        "LIKE",
                        "AND",
                        "OR",
                        "NOT",
                        "COUNT",
                        "COUNT_DISTINCT",
                        "MAX",
                        "MIN",
                        "AVG",
                        "SUM",
                        "ADD",
                        "SUB",
                        "MUL",
                        "DIV",
                        "MOD");
    }

    @Test
    @DisplayName("Operation 的按名解析可往返")
    void operationRoundTrip() {
        assertThat(Operation.valueOf("COUNT_DISTINCT")).isEqualTo(Operation.COUNT_DISTINCT);
        assertThat(Operation.valueOf("MOD")).isEqualTo(Operation.MOD);
    }

    @Test
    @DisplayName("LockMode 的常量集合由 api 自持，不借用任何后端枚举")
    void lockModeVocabulary() {
        assertThat(LockMode.values())
                .extracting(LockMode::name)
                .containsExactly(
                        "OPTIMISTIC",
                        "OPTIMISTIC_FORCE_INCREMENT",
                        "PESSIMISTIC_READ",
                        "PESSIMISTIC_WRITE",
                        "PESSIMISTIC_FORCE_INCREMENT");
        assertThat(LockMode.valueOf("PESSIMISTIC_WRITE")).isEqualTo(LockMode.PESSIMISTIC_WRITE);
    }
}
