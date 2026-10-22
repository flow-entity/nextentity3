package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/// 比较与集合算子的一致性：只看能力，不看生成的语句。
///
/// @param <T> 后端实体类型
public abstract class ComparisonConsistencyTest<T extends Item> extends ConsistencyTestBase<T> {

    /// `eq` 只命中相等行。
    @Test
    void eqMatchesExactlyOneRow() {
        List<T> rows = query().where(nameRef()).eq(BANANA_NAME).list();

        assertThat(rows).extracting(Item::id).containsExactly(BANANA_ID);
    }

    /// `ne` 排除相等行。
    @Test
    void neExcludesMatchingRow() {
        List<T> rows = query().where(nameRef()).ne(BANANA_NAME).list();

        assertThat(rows).extracting(Item::id).containsExactlyInAnyOrder(APPLE_ID, CHERRY_ID, APPLE_PIE_ID);
    }

    /// `eqIfNotNull(null)` 是**跳过条件**，不是「永不匹配」。
    @Test
    void eqIfNotNullWithNullSkipsCondition() {
        List<T> rows = query().where(nameRef()).eqIfNotNull(null).list();

        assertThat(rows).hasSize(4);
    }

    /// `eqIfNotNull(值)` 与 `eq` 同义。
    @Test
    void eqIfNotNullWithValueBehavesLikeEq() {
        List<T> rows = query().where(nameRef()).eqIfNotNull(CHERRY_NAME).list();

        assertThat(rows).extracting(Item::id).containsExactly(CHERRY_ID);
    }

    /// `in` 展开为集合匹配。
    @Test
    void inMatchesAnyOfValues() {
        List<T> rows = query().where(idRef()).in(List.of(APPLE_ID, CHERRY_ID)).list();

        assertThat(rows).extracting(Item::id).containsExactlyInAnyOrder(APPLE_ID, CHERRY_ID);
    }

    /// `in(空集合)` 退化为恒假：返回 0 行、不抛异常。
    @Test
    void inEmptyCollectionMatchesNothing() {
        List<T> rows = query().where(idRef()).in(List.of()).list();

        assertThat(rows).isEmpty();
    }

    /// `ge` + `le` 构成闭区间。
    @Test
    void geAndLeFormClosedRange() {
        List<T> rows = query().where(scoreRef()).ge(20).where(scoreRef()).le(30).list();

        assertThat(rows).extracting(Item::id).containsExactlyInAnyOrder(BANANA_ID, CHERRY_ID);
    }

    /// `gt` + `lt` 排除边界值。
    @Test
    void gtAndLtExcludeBoundaries() {
        List<T> rows = query().where(scoreRef()).gt(10).where(scoreRef()).lt(30).list();

        assertThat(rows).extracting(Item::id).containsExactly(BANANA_ID);
    }

    /// `geIfNotNull(null)` / `leIfNotNull(null)` 都跳过条件。
    @Test
    void rangeIfNotNullWithNullSkipsCondition() {
        List<T> rows = query().where(scoreRef())
                .geIfNotNull(null)
                .where(scoreRef())
                .leIfNotNull(null)
                .list();

        assertThat(rows).hasSize(4);
    }

    /// `between` 是闭区间（含两端）。
    @Test
    void betweenIncludesBothBounds() {
        List<T> rows = query().where(scoreRef()).between(10, 20).list();

        assertThat(rows).extracting(Item::id).containsExactlyInAnyOrder(APPLE_ID, BANANA_ID);
    }

    /// 枚举 `eq`：按枚举值比较（point-mall 有 51 处 `@Enumerated`，其中 50 处 STRING）。
    @Test
    void enumEqMatchesRowsWithThatStatus() {
        List<T> rows = query().where(statusRef()).eq(ItemStatus.NEW).list();

        assertThat(rows).extracting(Item::id).containsExactlyInAnyOrder(APPLE_ID, APPLE_PIE_ID);
    }

    /// `gtIfNotNull(null)` / `ltIfNotNull(null)` 都跳过条件。
    @Test
    void rangeIfNotNullWithNullOnBothSidesSkipsCondition() {
        List<T> rows = query().where(scoreRef())
                .gtIfNotNull(null)
                .where(scoreRef())
                .ltIfNotNull(null)
                .list();

        assertThat(rows).hasSize(4);
    }
}
