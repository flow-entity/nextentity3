package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.api.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// **变形关系**用例：不比对「期望值 vs 实际值」，而是比对**两条查询之间必须成立的关系**。
///
/// 与 `*DifferentialTest`（内存 oracle 独立算期望值）互补，解决两类 oracle 本身也答不出的问题：
///
/// - oracle 写错时差分套件会一起错（自己证明自己）；
/// - 差分的期望值必须逐格手算，覆盖不到「组合起来」的语义。
///
/// 本类只用**全称成立的关系**做断言，因此期望值不是「一个数」，而是「另一条查询的结果」，
/// 不需要有人知道正确答案是什么：
///
/// 1. **TLP**（三值逻辑分区，`WHERE` 子句的经典变形关系）：对任意条件 `p` 与任意条件 `c`，
///    `Q(p)` 的行集必须恰好等于 `Q(p ∧ c)`、`Q(p ∧ ¬c)`、`Q(p ∧ c IS NULL)` 三个桶的并集。
///    这条关系专门抓三类错：`AND`/`OR`/`NOT` 的优先级或分组错、`NOT` 与 `NULL` 相互作用写错、
///    条件被静默丢弃（丢弃时并集会少行或多行）。
/// 2. **幂等/中性元**：`¬¬p = p`、`p ∧ 恒真 = p`、`p ∧ 恒假 = ∅`、`p ∧ c = c ∧ p`。
///
/// 分页重组那一族关系在父类 {@link MetamorphicPagingTest} 里，这里不重复。
///
/// 每条关系都配了一条**自我检查**用例（{@link #offByOnePartitionIsNotIgnored()}）：故意把分区
/// 写成差一行，断言关系**确实会破**。没有这条，关系在实现里恒成立时看起来也是绿的——
/// 那就是「零断言入口」的变形版本。
///
/// @param <T> 后端实体类型
public abstract class MetamorphicQueryTest<T extends Item> extends MetamorphicPagingTest<T> {

    /// 一条可参与分区的数值列：名字用于失败信息，取值器用于在夹具上算边界。
    ///
    /// 用 record 而不是「列名字符串 + 三元选 getter」：后者会让同一个列名字面量在文件里出现两次，
    /// 触发 PMD 的 `AvoidDuplicateLiterals`，更要紧的是「名字」与「取值器」一旦不同源就会静默错位。
    private record ScoreColumn(String name, Function<FixtureRow, Integer> getter) {}

    @Override
    protected List<FixtureRow> fixtureRows() {
        return MetamorphicRows.rows();
    }

    // ---------------------------------------------------------------- 夹具自检

    /// 夹具必须支持分区：每个分区列的三类行都存在，且边界值在夹具里真实出现。
    ///
    /// 这条是下面所有 TLP 用例的**前置质量门**。分区退化（某桶为空、或边界值不存在）时，
    /// 等式照样成立，但已经测不出「少算一行」这类错误。
    @Test
    @DisplayName("变形夹具：每个分区列都存在大于/小于/为 null 三类行，且边界值真实出现")
    void fixtureSupportsEveryPartitionBucket() {
        List<FixtureRow> rows = fixtureRows();
        for (ScoreColumn column :
                List.of(new ScoreColumn("score", FixtureRow::score), new ScoreColumn("score2", FixtureRow::score2))) {
            Function<FixtureRow, Integer> getter = column.getter();
            int boundary = MetamorphicRows.boundary(rows, getter);
            long above = rows.stream()
                    .filter(row -> getter.apply(row) != null && getter.apply(row) > boundary)
                    .count();
            long below = rows.stream()
                    .filter(row -> getter.apply(row) != null && getter.apply(row) < boundary)
                    .count();
            long nulls = rows.stream().filter(row -> getter.apply(row) == null).count();
            long onBoundary = rows.stream()
                    .filter(row -> getter.apply(row) != null && getter.apply(row) == boundary)
                    .count();
            assertThat(above).as("%s 必须存在大于边界的行", column.name()).isPositive();
            assertThat(below).as("%s 必须存在小于边界的行", column.name()).isPositive();
            assertThat(nulls).as("%s 必须存在为 null 的行", column.name()).isPositive();
            assertThat(onBoundary)
                    .as("%s 必须存在恰好等于边界的行（否则分区边界本身没被测到）", column.name())
                    .isPositive();
        }
    }

    /// 退化格清单：`基谓词 | 分区`，这些格里至少有一桶为空，因此那一格的 TLP 等式**部分空转**。
    ///
    /// 为什么允许退化格存在、但要求逐格登记：退化成因有两类，**不要笼统说成「逻辑必然」**。
    /// 一类是**结构性**的：`p` 本身拒绝 `NULL`，于是 `p ∧ (列 IS NULL)` 桶恒空，换任何夹具都消不掉。
    /// 另一类只是**当前阈值**造成的：`p` 恰好蕴含 `c`（`score >= 50` 在 `c = score >= 48` 下成立），
    /// 把分区阈值调大就不再退化。只有「`neg` 与 `null` 同时为空」的格才真的零杀伤力——
    /// 那时等式退化成 `whole == pos`，只要 `and()` 原样返回左操作数就能过。
    ///
    /// 但「杀伤力为零的格」和「夹具悄悄退化的格」在运行结果里长得一模一样。所以把**当前**
    /// 退化格钉成常量：夹具一改、边界一漂，退化集合就会变，这条用例立刻红，逼人重新论证
    /// 每一格是「逻辑必然」还是「夹具没覆盖到」。没有这条，`fixtureSupportsEveryPartitionBucket`
    /// 只校验了逐列边缘分布，会给出「夹具已校验」的错觉。
    /// 逐格论证（括号内是当前夹具上的边界值；边界一变这些字符串就变，用例会红，这正是目的）：
    ///
    /// - `score >= 50 | score >= 48`：`p` 蕴含 `c`（50 ≥ 48），`neg` 必空；
    ///   且 `p` 本身拒绝 `NULL`，`p ∧ score IS NULL` 必空。
    /// - `score2 < 30 | score2 < 61`：同上（`p` 蕴含 `c`，且 `p` 拒绝 `NULL`）。
    /// - `note is not null | note = 'n2'`：`p ∧ note IS NULL` 必空——`p` 与分区空值判断
    ///   作用在同一列上，两者互斥。这是**结构性**退化，任何夹具都消不掉。
    /// - `score >= 20 AND score < 80 | score >= 48`：只有 `null` 桶空（`p` 拒绝 `NULL`）；
    ///   `neg` 桶（20 ≤ score < 48）非空，所以这一格仍然测得到「`NOT` 是否写对」。
    ///
    /// 判据：凡是 `neg` 与 `null` **同时**为空的格，TLP 等式退化成 `whole == pos`，
    /// 只要 `and()` 原样返回左操作数就能过——那两格（前两条）在 `c` 取值逼近 `p` 时就是这种形态。
    private static final List<String> KNOWN_DEGENERATE_CELLS = List.of(
            "score >= 50 | score >= 48",
            "score2 < 30 | score2 < 61",
            "note is not null | note = 'n2'",
            "score >= 20 AND score < 80 | score >= 48");

    @Test
    @DisplayName("变形夹具：退化为空桶的格必须恰好是已论证过的那几格")
    void degenerateCellsAreExactlyTheKnownOnes() {
        List<String> degenerate = new ArrayList<>();
        for (Named<T> base : basePredicates()) {
            for (Partition<T> partition : partitions()) {
                long positive =
                        query().where(base.predicate().and(partition.holds())).count();
                long negative =
                        query().where(base.predicate().and(partition.fails())).count();
                long nulls =
                        query().where(base.predicate().and(partition.isNull())).count();
                if (positive == 0 || negative == 0 || nulls == 0) {
                    degenerate.add(base.name() + " | " + partition.name());
                }
            }
        }
        assertThat(degenerate)
                .as("退化格集合变了：要么夹具漂移，要么新增了逻辑必然的退化格——逐格重新论证后改这个常量")
                .containsExactlyElementsOf(KNOWN_DEGENERATE_CELLS);
    }

    // ---------------------------------------------------------------- TLP

    @Test
    @DisplayName("TLP：Q(p) 的行集恰好等于 Q(p∧c)、Q(p∧¬c)、Q(p∧c IS NULL) 三桶之并")
    void truthPartitionCoversExactlyTheOriginalRows() {
        for (Named<T> base : basePredicates()) {
            for (Partition<T> partition : partitions()) {
                List<Long> whole = ids(query().where(base.predicate()));
                List<Long> positive = ids(query().where(base.predicate().and(partition.holds())));
                List<Long> negative = ids(query().where(base.predicate().and(partition.fails())));
                List<Long> nullBucket = ids(query().where(base.predicate().and(partition.isNull())));
                List<Long> union = new ArrayList<>(positive);
                union.addAll(negative);
                union.addAll(nullBucket);
                union.sort(Long::compareTo);
                assertThat(union)
                        .as("%s ÷ %s：三桶之并必须等于原行集", base.name(), partition.name())
                        .containsExactlyElementsOf(whole);
            }
        }
    }

    @Test
    @DisplayName("TLP：三桶两两不相交（等式不能靠重复计数凑出来）")
    void truthPartitionBucketsArePairwiseDisjoint() {
        for (Named<T> base : basePredicates()) {
            for (Partition<T> partition : partitions()) {
                List<Long> positive = ids(query().where(base.predicate().and(partition.holds())));
                List<Long> negative = ids(query().where(base.predicate().and(partition.fails())));
                List<Long> nullBucket = ids(query().where(base.predicate().and(partition.isNull())));
                assertDisjoint(
                        positive, negative, "%s ÷ %s：holds 与 NOT holds".formatted(base.name(), partition.name()));
                assertDisjoint(
                        positive, nullBucket, "%s ÷ %s：holds 与「列为 NULL」".formatted(base.name(), partition.name()));
                assertDisjoint(
                        negative, nullBucket, "%s ÷ %s：NOT holds 与「列为 NULL」".formatted(base.name(), partition.name()));
            }
        }
    }

    @Test
    @DisplayName("TLP（聚合）：Q(p).count() 等于三桶 count 之和")
    void truthPartitionCountsSumToTheOriginalCount() {
        for (Named<T> base : basePredicates()) {
            for (Partition<T> partition : partitions()) {
                long whole = query().where(base.predicate()).count();
                long sum = query().where(base.predicate().and(partition.holds()))
                                .count()
                        + query().where(base.predicate().and(partition.fails())).count()
                        + query().where(base.predicate().and(partition.isNull()))
                                .count();
                assertThat(sum)
                        .as("%s ÷ %s：三桶计数之和", base.name(), partition.name())
                        .isEqualTo(whole);
            }
        }
    }

    /// 自我检查：把分区故意写成「差一行」（`>` 代替 `>=`），TLP 关系**必须**被破坏。
    ///
    /// 这条用例不测生产代码，测的是**上面三条 TLP 用例的杀伤力**。它一旦变绿，说明夹具或条件
    /// 已经退化成「怎么写都对」，那三条 TLP 用例就成了摆设。
    @Test
    @DisplayName("TLP 自我检查：把 >= 写成 > 之后等式必须破（证明三条 TLP 用例有杀伤力）")
    void offByOnePartitionIsNotIgnored() {
        int boundary = MetamorphicRows.boundary(fixtureRows(), FixtureRow::score);
        Predicate<T> correct = Path.of(scoreRef()).ge(boundary);
        Predicate<T> offByOne = Path.of(scoreRef()).gt(boundary);
        Predicate<T> isNull = Path.of(scoreRef()).isNull();

        // 故意把负桶写成严格小于（`<` 而不是 `NOT (>=)`）：边界行 score == boundary
        // 既不满足 `>`、也不满足 `<`、更不是 null，于是三桶不再覆盖全集。
        // 手写分区时最容易犯的就是这一格；`NOT c` 反而不会漏（它会命中边界行）。
        List<Long> whole = ids(query());

        List<Long> mutated = new ArrayList<>(ids(query().where(offByOne)));
        mutated.addAll(ids(query().where(Path.of(scoreRef()).lt(boundary))));
        mutated.addAll(ids(query().where(isNull)));
        mutated.sort(Long::compareTo);
        assertThat(mutated).as("score > %d 的三桶之并必须漏掉边界行，等式必须破", boundary).isNotEqualTo(whole);

        // 同一套三桶写法，正确的 >= 必须严丝合缝：证明上一条的破坏来自边界错位，而不是三桶写法本身有问题。
        List<Long> proper = new ArrayList<>(ids(query().where(correct)));
        proper.addAll(ids(query().where(correct.not())));
        proper.addAll(ids(query().where(isNull)));
        proper.sort(Long::compareTo);
        assertThat(proper).as("同样的三桶写法，正确的 score >= %d 分区必须等于全集", boundary).containsExactlyElementsOf(whole);

        // 而且丢掉的必须恰好是边界行——否则「等式破了」可能来自别的原因，上面那条就不算证据。
        List<Long> missing = new ArrayList<>(whole);
        missing.removeAll(mutated);
        List<Long> boundaryRows = fixtureRows().stream()
                .filter(row -> row.score() != null && row.score() == boundary)
                .map(FixtureRow::id)
                .toList();
        assertThat(missing).as("漏掉的必须恰好是 score == %d 的行", boundary).containsExactlyElementsOf(boundaryRows);
    }

    // ---------------------------------------------------------------- 幂等/中性元

    @Test
    @DisplayName("变形：双重取反恒等于原条件")
    void doubleNegationIsIdentity() {
        for (Named<T> base : basePredicates()) {
            assertThat(ids(query().where(base.predicate().not().not())))
                    .as("%s：¬¬p 必须等于 p", base.name())
                    .containsExactlyElementsOf(ids(query().where(base.predicate())));
        }
    }

    @Test
    @DisplayName("变形：与恒真合取不改变结果，与恒假合取得空集")
    void conjunctionWithTautologyAndContradiction() {
        Predicate<T> tautology =
                Path.of(scoreRef()).isNull().or(Path.of(scoreRef()).isNotNull());
        Predicate<T> contradiction =
                Path.of(scoreRef()).isNull().and(Path.of(scoreRef()).isNotNull());
        for (Named<T> base : basePredicates()) {
            assertThat(ids(query().where(base.predicate().and(tautology))))
                    .as("%s：p ∧ 恒真 = p", base.name())
                    .containsExactlyElementsOf(ids(query().where(base.predicate())));
            assertThat(ids(query().where(base.predicate().and(contradiction))))
                    .as("%s：p ∧ 恒假 = ∅", base.name())
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("变形：合取可交换（a ∧ b 与 b ∧ a 行集相同）")
    void conjunctionIsCommutative() {
        for (Named<T> base : basePredicates()) {
            for (Partition<T> partition : partitions()) {
                assertThat(ids(query().where(base.predicate().and(partition.holds()))))
                        .as("%s ÷ %s：a ∧ b 必须等于 b ∧ a", base.name(), partition.name())
                        .containsExactlyElementsOf(
                                ids(query().where(partition.holds().and(base.predicate()))));
            }
        }
    }
}
