package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/// 列对列比较的一致性：右侧是**表达式**（另一列）而不是字面量。
///
/// 夹具两列 `score` / `score2` 同型（`Integer`），按手算：
/// ```text
/// #1 score=10   score2=10    → = 成立、>= 成立、<= 成立
/// #2 score=20   score2=10    → > 成立、>= 成立、<> 成立
/// #3 score=30   score2=null  → 所有比较都是 UNKNOWN
/// #4 score=null score2=5     → 所有比较都是 UNKNOWN
/// ```
///
/// **三值逻辑说明（这是 SQL 语义，不是缺陷）**：任一侧为 `null` 时比较结果是 `UNKNOWN`，
/// 而 `WHERE` 只保留 `TRUE`，所以 #3 / #4 既不满足 `a = b` 也不满足 `a <> b`，
/// 也不会出现在 `between`（或 `notBetween`）的任何一侧。
///
/// @param <T> 后端实体类型
public abstract class ColumnComparisonConsistencyTest<T extends Item> extends ConsistencyTestBase<T> {

    /// `score = score2` 只命中两列相等且都非空的行。
    @Test
    void columnEqMatchesRowsWithEqualColumns() {
        List<T> rows = query().where(scoreRef()).eq(Path.of(score2Ref())).list();

        assertThat(rows).extracting(Item::id).containsExactly(APPLE_ID);
    }

    /// `score > score2` 只命中列严格更大的行。
    @Test
    void columnGtMatchesRowsWithGreaterLeftColumn() {
        List<T> rows = query().where(scoreRef()).gt(Path.of(score2Ref())).list();

        assertThat(rows).extracting(Item::id).containsExactly(BANANA_ID);
    }

    /// `score < score2` 在夹具上无命中（#4 是 `null < 5` = UNKNOWN，不算命中）。
    @Test
    void columnLtMatchesNothing() {
        List<T> rows = query().where(scoreRef()).lt(Path.of(score2Ref())).list();

        assertThat(rows).isEmpty();
    }

    /// `score >= score2` 命中「相等」与「左侧更大」两类行（20 >= 10 也成立）。
    @Test
    void columnGeMatchesRowsWithGreaterOrEqualColumns() {
        List<T> rows = query().where(scoreRef()).ge(Path.of(score2Ref())).list();

        assertThat(rows).extracting(Item::id).containsExactlyInAnyOrder(APPLE_ID, BANANA_ID);
    }

    /// `score <= score2` 命中两列相等的行。
    @Test
    void columnLeMatchesRowsWithLessOrEqualColumns() {
        List<T> rows = query().where(scoreRef()).le(Path.of(score2Ref())).list();

        assertThat(rows).extracting(Item::id).containsExactly(APPLE_ID);
    }

    /// `score <> score2` 只命中两列不相等且都非空的行。
    @Test
    void columnNeMatchesRowsWithDifferentColumns() {
        List<T> rows = query().where(scoreRef()).ne(Path.of(score2Ref())).list();

        assertThat(rows).extracting(Item::id).containsExactly(BANANA_ID);
    }

    /// 列对列 `between`：`score between score2 and score2` 等价于列相等。
    @Test
    void columnBetweenWithColumnBoundsMatchesEqualColumns() {
        List<T> rows = query().where(scoreRef())
                .between(Path.of(score2Ref()), Path.of(score2Ref()))
                .list();

        assertThat(rows).extracting(Item::id).containsExactly(APPLE_ID);
    }

    /// 三值逻辑：`=` 与 `<>` 的命中集合加起来**不等于**全表，NULL 行两边都不在。
    @Test
    void nullOperandsSatisfyNeitherEqNorNe() {
        List<T> equal = query().where(scoreRef()).eq(Path.of(score2Ref())).list();
        List<T> different = query().where(scoreRef()).ne(Path.of(score2Ref())).list();

        assertThat(equal).extracting(Item::id).containsExactly(APPLE_ID);
        assertThat(different).extracting(Item::id).containsExactly(BANANA_ID);
        // #3（score2 = null）与 #4（score = null）是 UNKNOWN，既不等于也不不等于。
        assertThat(equal).extracting(Item::id).doesNotContain(CHERRY_ID, APPLE_PIE_ID);
        assertThat(different).extracting(Item::id).doesNotContain(CHERRY_ID, APPLE_PIE_ID);
    }
}
