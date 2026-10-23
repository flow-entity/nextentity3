package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/// 表达式算术的一致性：`add` / `sub` / `mul` / `div` / `mod`，以及链式算术与算术投影。
///
/// 夹具 `score` = 10 / 20 / 30 / null（按 id 1..4），逐条手算：
/// ```text
/// mod 3 → 1 / 2 / 0 / null      add 5 → 15 / 25 / 35 / null
/// sub 5 → 5 / 15 / 25 / null    mul 2 → 20 / 40 / 60 / null
/// div 2 → 5 / 10 / 15 / null    mod(3).add(1) → 2 / 3 / 1 / null
/// ```
///
/// 两个刻意钉住的语义：
/// - **NULL 传播**：任一侧为 `null` 时整个算术结果是 `null`，比较为 `UNKNOWN` → 不命中；
/// - **除法只断言整除**：非整除时 `10 / 4` 在 H2 是整数除法（2）、在 MySQL 是精确小数（2.5），
///   方言不一致，本类不断言这种值（要断言就得绑定具体方言，那不是一致性用例的职责）。
///
/// @param <T> 后端实体类型
public abstract class ArithmeticConsistencyTest<T extends Item> extends ConsistencyTestBase<T> {

    /// `score mod 3 = 0` 只命中 30。
    @Test
    void modFiltersByRemainder() {
        List<T> rows = query().where(Path.of(scoreRef()).mod(3).eq(0)).list();

        assertThat(rows).extracting(Item::id).containsExactly(CHERRY_ID);
    }

    /// `score + 5 = 25` 只命中 20。
    @Test
    void addShiftsValue() {
        List<T> rows = query().where(Path.of(scoreRef()).add(5).eq(25)).list();

        assertThat(rows).extracting(Item::id).containsExactly(BANANA_ID);
    }

    /// `score - 5 = 5` 只命中 10。
    @Test
    void subShiftsValue() {
        List<T> rows = query().where(Path.of(scoreRef()).sub(5).eq(5)).list();

        assertThat(rows).extracting(Item::id).containsExactly(APPLE_ID);
    }

    /// `score * 2 = 40` 只命中 20。
    @Test
    void mulScalesValue() {
        List<T> rows = query().where(Path.of(scoreRef()).mul(2).eq(40)).list();

        assertThat(rows).extracting(Item::id).containsExactly(BANANA_ID);
    }

    /// `score / 2 = 10` 只命中 20（整除，跨方言一致）。
    @Test
    void divDividesValue() {
        List<T> rows = query().where(Path.of(scoreRef()).div(2).eq(10)).list();

        assertThat(rows).extracting(Item::id).containsExactly(BANANA_ID);
    }

    /// 链式算术：`(score mod 3) + 1 = 1` 只命中 30。
    @Test
    void chainedArithmeticAppliesLeftToRight() {
        List<T> rows = query().where(Path.of(scoreRef()).mod(3).add(1).eq(1)).list();

        assertThat(rows).extracting(Item::id).containsExactly(CHERRY_ID);
    }

    /// 算术进投影：按 id 升序取出 `score mod 3`，第四行是 `null`（NULL 传播）。
    @Test
    void arithmeticProjectionKeepsNullForNullOperand() {
        List<Integer> remainders = query().select(Path.of(scoreRef()).mod(3))
                .orderBy(idRef())
                .asc()
                .list();

        assertThat(remainders).containsExactly(1, 2, 0, null);
    }
}
