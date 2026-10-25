package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.BaseWhereStep;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.Predicate;
import java.util.List;

/// 变形关系用例的共享底座：只放**参数**与**断言工具**，不放具体关系。
///
/// 拆出这一层是为了让两族关系（分区/TLP 与分页重组）各自独立成类：`MetamorphicQueryTest` 与
/// `MetamorphicPagingTest` 都只关心「关系怎么写」，夹具参数只在这里定义一次，避免两族关系
/// 各自维护一份 `basePredicates()` 而慢慢跑偏。
///
/// @param <T> 后端实体类型
public abstract class MetamorphicTestBase<T extends Item> extends ConsistencyTestBase<T> {

    /// 一条具名谓词：失败信息里带名字，不必靠行号猜是哪一格。
    protected record Named<T>(String name, Predicate<T> predicate) {}

    /// 一个三值逻辑分区：`holds` / `NOT holds` / 「`holds` 那列为 `NULL`」三桶互斥且穷尽。
    ///
    /// `fails` 与 `isNull` 都由 `holds` 派生，不单独写——否则三条条件一旦不同源，
    /// 等式会因「三桶其实不是同一个条件的分区」而失去意义。
    protected record Partition<T>(String name, Predicate<T> holds, Predicate<T> fails, Predicate<T> isNull) {}

    /// 被分区的基谓词清单：覆盖数值区间、文本前缀、空值判断、枚举相等与两段合取。
    ///
    /// 这段清单本身就是覆盖证据：只有它同时含「真/假/NULL 三类行」的条件多，TLP 才可能真的
    /// 分区出三桶。夹具质量由 `MetamorphicQueryTest#fixtureSupportsEveryPartitionBucket` 前置校验。
    protected List<Named<T>> basePredicates() {
        return List.of(
                new Named<>("score >= 50", Path.of(scoreRef()).ge(50)),
                new Named<>("score2 < 30", Path.of(score2Ref()).lt(30)),
                new Named<>("name in ('apple', 'cherry')", Path.of(nameRef()).in("apple", "cherry")),
                new Named<>("note is not null", Path.of(noteRef()).isNotNull()),
                new Named<>("status = NEW", Path.of(statusRef()).eq(ItemStatus.NEW)),
                new Named<>(
                        "score >= 20 AND score < 80",
                        Path.of(scoreRef()).ge(20).and(Path.of(scoreRef()).lt(80))));
    }

    /// 分区条件清单：两条数值列（边界取夹具有的中位数）+ 一条文本列。
    protected List<Partition<T>> partitions() {
        int scoreBoundary = MetamorphicRows.boundary(fixtureRows(), FixtureRow::score);
        int score2Boundary = MetamorphicRows.boundary(fixtureRows(), FixtureRow::score2);
        return List.of(
                partition(
                        "score >= " + scoreBoundary,
                        Path.of(scoreRef()).ge(scoreBoundary),
                        Path.of(scoreRef()).isNull()),
                partition(
                        "score2 < " + score2Boundary,
                        Path.of(score2Ref()).lt(score2Boundary),
                        Path.of(score2Ref()).isNull()),
                partition(
                        "note = 'n2'",
                        Path.of(noteRef()).eq("n2"),
                        Path.of(noteRef()).isNull()));
    }

    private static <T> Partition<T> partition(String name, Predicate<T> holds, Predicate<T> isNull) {
        return new Partition<>(name, holds, holds.not(), isNull);
    }

    /// 断言两个 id 列表无交集。
    ///
    /// 不用 AssertJ 的 `doesNotContainAnyElementsOf`：它在入参为空集合时直接抛异常，
    /// 而「空桶」恰恰是分区退化的常见形态，那时应该判「不相交成立」而不是报错。
    protected void assertDisjoint(List<Long> left, List<Long> right, String description) {
        List<Long> intersection = left.stream().filter(right::contains).toList();
        assertThat(intersection).as("%s 必须不相交", description).isEmpty();
    }

    /// 取一次查询的 id 序列（按 id 升序）。变形关系比的是**行集**，排序只是为了让失败信息稳定、
    /// 且让「顺序不同」不被误判成「行集不同」。
    protected List<Long> ids(BaseWhereStep<T, T> step) {
        return step.orderBy(idRef()).asc().list().stream().map(Item::id).toList();
    }
}
