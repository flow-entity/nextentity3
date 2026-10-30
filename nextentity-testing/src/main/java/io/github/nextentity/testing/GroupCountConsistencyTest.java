package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `groupBy` 之后的 `count()` / `exists()`：语义是"组数 / 是否有组"，期望值由 {@link FixtureRow}
/// 在内存里分组数出来，与后端无关。窗口分页在 [`GroupWindowConsistencyTest`] 单独一组。
///
/// 这是**后端中立**的契约用例：同一个子类在 JPA 与 JDBC 后端上必须得到同样的结果。
///
/// @param <T> 后端实体类型
public abstract class GroupCountConsistencyTest<T extends Item> extends ConsistencyTestBase<T> {

    @Test
    @DisplayName("count() 数的是组数，不是行数")
    void countCountsGroups() {
        assertThat(query().groupBy(statusRef()).count()).isEqualTo(expectedGroupCount());
    }

    @Test
    @DisplayName("count() 尊重 having：数的是过滤后剩下的组")
    void countRespectsHaving() {
        long moreThanOne = query().groupBy(statusRef())
                .having(Path.of(idRef()).count().gt(1L))
                .count();
        long moreThanFive = query().groupBy(statusRef())
                .having(Path.of(idRef()).count().gt(5L))
                .count();

        assertThat(moreThanOne).isEqualTo(expectedGroupsLargerThan(1));
        assertThat(moreThanFive).isZero();
    }

    @Test
    @DisplayName("exists() 回答\"有没有满足 having 的组\"")
    void existsAnswersWhetherAnyGroupSurvivesHaving() {
        assertThat(query().groupBy(statusRef()).exists()).isTrue();
        assertThat(query().groupBy(statusRef())
                        .having(Path.of(idRef()).count().gt(1L))
                        .exists())
                .isTrue();
        assertThat(query().groupBy(statusRef())
                        .having(Path.of(idRef()).count().gt(5L))
                        .exists())
                .isFalse();
    }

    @Test
    @DisplayName("空结果集：0 组、没有任何组")
    void emptyTableHasNoGroups() {
        support().seedData(List.of());

        assertThat(query().groupBy(statusRef()).count()).isZero();
        assertThat(query().groupBy(statusRef()).exists()).isFalse();
    }

    @Test
    @DisplayName("没有 groupBy 的聚合形态仍显式拒绝")
    void aggregateWithoutGroupByIsRejected() {
        assertThatThrownBy(() -> query().select(Path.of(idRef()).count()).count())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> query().select(Path.of(idRef()).count()).exists())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private long expectedGroupCount() {
        return fixtureRows().stream().map(FixtureRow::status).distinct().count();
    }

    private long expectedGroupsLargerThan(long size) {
        return fixtureRows().stream()
                .collect(Collectors.groupingBy(FixtureRow::status, Collectors.counting()))
                .values()
                .stream()
                .filter(count -> count > size)
                .count();
    }
}
