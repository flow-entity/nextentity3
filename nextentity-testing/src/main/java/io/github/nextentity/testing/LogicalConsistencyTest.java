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
}
