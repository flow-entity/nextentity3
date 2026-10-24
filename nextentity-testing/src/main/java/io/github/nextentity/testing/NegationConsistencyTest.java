package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.api.Predicate;
import java.util.List;
import org.junit.jupiter.api.Test;

/// `not()` 取反的一致性：单层取反、链式取反（双重/三重否定）、短路占位取反。
///
/// 单独成类而不是并进 `LogicalConsistencyTest`：`not()` 是唯一会被后端渲染成**嵌套 `NOT`** 的算子，
/// 而嵌套 `NOT` 在 Hibernate 上不可靠（H2 与 MySQL 都能复现，见 `OpInterpreter#negate`），
/// 取反因此需要自己的一组证据；`LogicalConsistencyTest` 的方法数也接近 PMD `TooManyMethods` 的上限（12）。
///
/// @param <T> 后端实体类型
public abstract class NegationConsistencyTest<T extends Item> extends ConsistencyTestBase<T> {

    /// 单层取反：`NOT (name = banana)` 命中其余三行。
    @Test
    void notNegatesPredicate() {
        Predicate<T> notBanana = Path.of(nameRef()).eq(BANANA_NAME).not();

        List<T> rows = query().where(notBanana).list();

        assertThat(rows).extracting(Item::id).containsExactlyInAnyOrder(APPLE_ID, CHERRY_ID, APPLE_PIE_ID);
    }

    /// 双重否定回到原条件，三重否定回到一层 `NOT`（消去在 `OpInterpreter#negate`）。
    ///
    /// 奇偶性由本用例守住：三重否定若落成「无 NOT」或「两层 NOT」，说明消去算错了。
    @Test
    void doubleNotCancelsOutAndTripleKeepsOne() {
        List<T> twice =
                query().where(Path.of(nameRef()).eq(BANANA_NAME).not().not()).list();
        assertThat(twice).extracting(Item::id).containsExactly(BANANA_ID);

        List<T> thrice = query().where(
                        Path.of(nameRef()).eq(BANANA_NAME).not().not().not())
                .list();
        assertThat(thrice).extracting(Item::id).containsExactlyInAnyOrder(APPLE_ID, CHERRY_ID, APPLE_PIE_ID);
    }

    /// 恒真占位取反是恒假：`eqIfNotNull(null)` 是「不加这条条件」，取反即 `WHERE 1=0`，命中 0 行；
    /// 再取反一次回到恒真，等于「没有条件」，命中全部行。
    ///
    /// 这条同时锁住「取反不会把短路占位当成普通谓词」：若实现把占位当普通谓词再拼 `NOT`，
    /// 单层取反会变成「命中全部行」，与 `LogicalOps#not` 的契约相反。
    ///
    /// 双重取反后的「全部行」钉**具体 id 集合**：夹具恰好 4 行，只断 `hasSize(4)` 时，
    /// 丢掉一行又重复另一行、或条件错写成命中另一批 4 行，都照样通过。
    @Test
    void notOnShortCircuitPlaceholderMatchesNothing() {
        assertThat(query().where(Path.of(nameRef()).eqIfNotNull(null).not()).list())
                .isEmpty();
        assertThat(query().where(Path.of(nameRef()).eqIfNotNull(null).not().not())
                        .list())
                .extracting(Item::id)
                .containsExactlyInAnyOrder(APPLE_ID, BANANA_ID, CHERRY_ID, APPLE_PIE_ID);
    }
}
