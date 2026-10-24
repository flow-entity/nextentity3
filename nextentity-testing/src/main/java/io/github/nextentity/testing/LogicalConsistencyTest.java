package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.api.Predicate;
import java.util.List;
import org.junit.jupiter.api.Test;

/// 逻辑组合与空值判定的一致性。
///
/// `or` 用例是本类重点：`or` 必须整体加括号，否则后续条件会与其中一支抢先结合，结果从 2 行变成
/// 0/1 行——这类偏差在语句文本里看不出来，只有结果能抓住。
///
/// 集合形态的语义（`and(Collection)` / `or(Collection)`）：**空集合不会吞掉 receiver**，
/// `a ∧ 空 = a`、`a ∨ 空 = a`。生产形态是 `where(租户边界).and(动态条件列表)`，动态列表为空是常态，
/// 若退化成恒真/恒假就等于静默放宽或清空租户边界，所以这两条必须由用例钉住。
///
/// 组合条件用 `Path.of(...)` 构造（`Path` 的算子返回 `Predicate`，只有 `Predicate` 才可 `and`/`or`）。
///
/// @param <T> 后端实体类型
public abstract class LogicalConsistencyTest<T extends Item> extends ConsistencyTestBase<T> {

    /// `or` 分组：`name = banana OR score > 25` → banana + cherry。
    @Test
    void orGroupsConditionsTogether() {
        Predicate<T> either = Path.of(nameRef()).eq(BANANA_NAME).or(scoreRef()).gt(25);

        List<T> rows = query().where(either).list();

        assertThat(rows).extracting(Item::id).containsExactlyInAnyOrder(BANANA_ID, CHERRY_ID);
    }

    /// `or` 之后再追加条件：新条件与整组 `or` 做 AND。
    @Test
    void whereAfterOrAppliesToWholeOrGroup() {
        Predicate<T> either = Path.of(nameRef()).eq(BANANA_NAME).or(scoreRef()).gt(25);

        List<T> rows = query().where(either).where(scoreRef()).ge(30).list();

        assertThat(rows).extracting(Item::id).containsExactly(CHERRY_ID);
    }

    /// 两个 `Predicate` 之间用 `or` 合并（point-mall 的借贷双条件写法）。
    @Test
    void orCombinesTwoPredicates() {
        Predicate<T> first = Path.of(nameRef()).eq(APPLE_NAME);
        Predicate<T> second = Path.of(idRef()).eq(BANANA_ID);

        List<T> rows = query().where(first.or(second)).list();

        assertThat(rows).extracting(Item::id).containsExactlyInAnyOrder(APPLE_ID, BANANA_ID);
    }

    /// `and` 组合两个条件。
    @Test
    void andCombinesTwoPredicates() {
        Predicate<T> both = Path.of(scoreRef()).lt(20).and(Path.of(idRef()).eq(APPLE_ID));

        List<T> rows = query().where(both).list();

        assertThat(rows).extracting(Item::id).containsExactly(APPLE_ID);
    }

    /// `isNull` 只命中空值行。
    @Test
    void isNullMatchesOnlyNullRows() {
        List<T> rows = query().where(noteRef()).isNull().list();

        assertThat(rows).extracting(Item::id).containsExactlyInAnyOrder(APPLE_ID, APPLE_PIE_ID);
    }

    /// `isNotNull` 与 `isNull` 互补。
    @Test
    void isNotNullExcludesNullRows() {
        List<T> rows = query().where(noteRef()).isNotNull().list();

        assertThat(rows).extracting(Item::id).containsExactlyInAnyOrder(BANANA_ID, CHERRY_ID);
    }

    /// n 元 `and(Collection)`：所有条件同时成立。
    @Test
    void andCollectionRequiresAllConditions() {
        Predicate<T> both = Path.of(scoreRef())
                .ge(20)
                .and(List.of(Path.of(scoreRef()).le(30), Path.of(nameRef()).eq(CHERRY_NAME)));

        List<T> rows = query().where(both).list();

        assertThat(rows).extracting(Item::id).containsExactly(CHERRY_ID);
    }

    /// n 元 `or(Collection)`：任一条件成立即可。
    @Test
    void orCollectionRequiresAnyCondition() {
        Predicate<T> either = Path.of(nameRef())
                .eq(BANANA_NAME)
                .or(List.of(Path.of(scoreRef()).gt(25), Path.of(nameRef()).eq(MISSING_NAME)));

        List<T> rows = query().where(either).list();

        assertThat(rows).extracting(Item::id).containsExactlyInAnyOrder(BANANA_ID, CHERRY_ID);
    }

    /// `and(空集合)` 与 `or(空集合)` 都等于 **receiver 本身**：`a ∧ 空 = a`、`a ∨ 空 = a`。
    ///
    /// 生产形态是 `where(租户边界).and(动态条件列表)`，动态列表为空是常态；
    /// 若这里退化成恒真/恒假，等于**静默放宽或清空租户边界**（跨租户泄漏 / 全表空结果）。
    @Test
    void emptyCollectionKeepsReceiverForBothOperators() {
        List<T> andRows =
                query().where(Path.of(nameRef()).eq(BANANA_NAME).and(List.of())).list();
        List<T> orRows =
                query().where(Path.of(nameRef()).eq(BANANA_NAME).or(List.of())).list();

        assertThat(andRows).extracting(Item::id).containsExactly(BANANA_ID);
        assertThat(orRows).extracting(Item::id).containsExactly(BANANA_ID);
    }

    /// `or` 里混入 `eqIfNotNull(null)`（短路成恒真的占位条件）：`TRUE ∨ x = TRUE` → 整条恒真。
    ///
    /// 恒真必须钉**具体 id 集合**而不是 `hasSize(4)`：夹具恰好 4 行，任何恒真条件（含把短路占位
    /// 静默丢掉的实现）都得到 4 行，行数对内容错时 `hasSize` 一律假绿。
    @Test
    void orWithShortCircuitedConditionIsTautology() {
        Predicate<T> either =
                Path.of(nameRef()).eq(BANANA_NAME).or(List.of(Path.of(nameRef()).eqIfNotNull(null)));

        List<T> rows = query().where(either).list();

        assertThat(rows).extracting(Item::id).containsExactlyInAnyOrder(APPLE_ID, BANANA_ID, CHERRY_ID, APPLE_PIE_ID);
    }

    /// `and` 里混入 `eqIfNotNull(null)`（恒真占位）：`a ∧ TRUE = a` → 仍是 receiver。
    @Test
    void andWithShortCircuitedConditionKeepsReceiver() {
        Predicate<T> onlyBanana = Path.of(nameRef())
                .eq(BANANA_NAME)
                .and(List.of(Path.of(nameRef()).eqIfNotNull(null)));

        List<T> rows = query().where(onlyBanana).list();

        assertThat(rows).extracting(Item::id).containsExactly(BANANA_ID);
    }
}
