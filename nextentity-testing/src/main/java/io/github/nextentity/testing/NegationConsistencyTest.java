package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.api.Predicate;
import java.util.List;
import org.junit.jupiter.api.Test;

/// `not()` 取反的一致性：单层取反、链式取反（双重/三重否定）、短路占位取反。
///
/// 为什么单独成类而不是并进 `LogicalConsistencyTest`：`not()` 是唯一会被后端渲染成**嵌套 `NOT`** 的算子，
/// 而嵌套 `NOT` 恰好是 Hibernate 的薄弱点——`Predicate.not()` 对「最内层是比较谓词」的嵌套取反
/// **只生效一次**（H2 与 MySQL 都能复现，见 `nextentity-jpa` 的渲染用例）。把「取反」单列，
/// 既让这条语义在两条通道上各有独立证据，也避免 `LogicalConsistencyTest` 撞上 `TooManyMethods`（上限 12）。
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

    /// 双重否定回到原条件，三重否定回到一层 `NOT`。
    ///
    /// 钉它的理由：Hibernate 的 `Predicate.not()` 对「最内层是比较谓词」的嵌套取反只生效一次，
    /// 把 `NOT(NOT(x))` 原样下推会得到 `NOT(x)` 的结果（`not().not()` 静默退化）。
    /// core 在 `OpInterpreter#negate` 里消去双重否定；本用例守规矩，也守住奇偶性——
    /// 三重否定若落成「无 NOT」或「两层 NOT」，说明消去算错了。
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
    @Test
    void notOnShortCircuitPlaceholderMatchesNothing() {
        assertThat(query().where(Path.of(nameRef()).eqIfNotNull(null).not()).list())
                .isEmpty();
        assertThat(query().where(Path.of(nameRef()).eqIfNotNull(null).not().not())
                        .list())
                .hasSize(4);
    }
}
