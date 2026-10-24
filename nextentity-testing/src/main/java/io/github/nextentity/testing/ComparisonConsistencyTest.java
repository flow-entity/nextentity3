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

    /// `eqIfNotNull(null)` 是**跳过条件**，不是「永不匹配」：命中全表 4 行。
    ///
    /// 钉具体 id 集合而不是 `hasSize(4)`：只数个数时，「跳过写成恒假再兜成全部行」之外的
    /// 大量偏差都能蒙过去——重复行凑够 4 条、丢掉一行又重复另一行、把条件错写成
    /// `name <> null` 却恰好 4 行（全表就 4 行，任何恒真条件都得到 4）。改成 id 集合后，
    /// 少一行、多一行、换成别的行都会红。
    @Test
    void eqIfNotNullWithNullSkipsCondition() {
        List<T> rows = query().where(nameRef()).eqIfNotNull(null).list();

        assertThat(rows).extracting(Item::id).containsExactlyInAnyOrder(APPLE_ID, BANANA_ID, CHERRY_ID, APPLE_PIE_ID);
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

    /// `geIfNotNull(null)` / `leIfNotNull(null)` 都跳过条件：命中全表 4 行（id 集合钉死）。
    @Test
    void rangeIfNotNullWithNullSkipsCondition() {
        List<T> rows = query().where(scoreRef())
                .geIfNotNull(null)
                .where(scoreRef())
                .leIfNotNull(null)
                .list();

        assertThat(rows).extracting(Item::id).containsExactlyInAnyOrder(APPLE_ID, BANANA_ID, CHERRY_ID, APPLE_PIE_ID);
    }

    /// `between` 是闭区间（含两端）。
    @Test
    void betweenIncludesBothBounds() {
        List<T> rows = query().where(scoreRef()).between(10, 20).list();

        assertThat(rows).extracting(Item::id).containsExactlyInAnyOrder(APPLE_ID, BANANA_ID);
    }

    /// `gtIfNotNull(null)` / `ltIfNotNull(null)` 都跳过条件：命中全表 4 行（id 集合钉死）。
    @Test
    void rangeIfNotNullWithNullOnBothSidesSkipsCondition() {
        List<T> rows = query().where(scoreRef())
                .gtIfNotNull(null)
                .where(scoreRef())
                .ltIfNotNull(null)
                .list();

        assertThat(rows).extracting(Item::id).containsExactlyInAnyOrder(APPLE_ID, BANANA_ID, CHERRY_ID, APPLE_PIE_ID);
    }

    /// `notBetween` 与 `between` 互补：两者命中集合的并集是「两端都非空」的行，NULL 行两边都不在。
    @Test
    void notBetweenComplementsBetween() {
        List<T> inside = query().where(scoreRef()).between(10, 20).list();
        List<T> outside = query().where(scoreRef()).notBetween(10, 20).list();

        assertThat(inside).extracting(Item::id).containsExactlyInAnyOrder(APPLE_ID, BANANA_ID);
        assertThat(outside).extracting(Item::id).containsExactly(CHERRY_ID);
        // score = null 的 #4 是 UNKNOWN：既不在区间内，也不在区间外。
        assertThat(inside).extracting(Item::id).doesNotContain(APPLE_PIE_ID);
        assertThat(outside).extracting(Item::id).doesNotContain(APPLE_PIE_ID);
    }
}
