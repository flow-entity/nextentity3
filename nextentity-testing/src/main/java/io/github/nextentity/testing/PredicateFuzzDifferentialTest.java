package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/// 谓词层的属性测试：同一个 seed 生成的**一棵与实现无关的概念树**，分别降给两个互不相邻的渲染器。
///
/// 差分口径（`AGENTS.md`「测试编写红线」第 1 条、`docs/DESIGN.md` §5「oracle 分层」）：
/// 期望值不手算，由 [Memory] 在 [Fixtures] 的算子夹具上独立做 Kleene 三值求值得到；实际值由
/// [Backend] 把同一棵树降成 DSL 查询后交给真库执行。两个渲染器都只读概念树，谁也不看对方，
/// 谁也不回读对方的结果——只有这样才能互相证伪。
///
/// 为什么不是「随机生成两种表示」：那样两个表示各自都可能带着同一个理解偏差，差分会退化成
/// 「实现与它的镜像相等」。切成「概念树 → 两条独立渲染路径」之后，任一路径理解错（例如把
/// `NULL` 行算进 `<>` 或算进 `BETWEEN`）都会让两边的 id 列表分叉。
///
/// 树的形状由 seed 决定（见 [Generator] 的分布说明），叶子上限 3、组合子上限 3：
/// 上限比任务允许的 5 叶 / 3 层更紧，是为了让每个 seed 的用例名短到能一眼读完。
///
/// 内存一侧的行数据取自 `fixtureRows()`（播种用的就是它），不硬编码 {@link Fixtures#OPERATOR_ROWS}：
/// 子类覆写 `fixtureRows()` 时两边必须是同一份行，否则差分静默假绿。
///
/// @param <T> 实体类型
public abstract class PredicateFuzzDifferentialTest<T extends Item> extends ConsistencyTestBase<T> {

    /// seed 总数。用固定区间而不是随机源：失败可复现，也不触碰 `NO_AMBIENT_CLOCK_OR_RANDOM`。
    private static final int SEED_COUNT = 60;

    /// 落在「中间态」的 seed 数下限——期望值是空集或全表时这条用例没有区分力。
    ///
    /// 实测 60 个 seed 里有 46 个落在中间态（12 个空集、2 个全表）：三种形状各 20 个，
    /// 空集主要来自「组合子 + 另一列叶子」这种合取形状——6 行夹具上两个条件不相交是常态。
    /// 下限钉在实测值上（46，不是打折后的数）：生成器、取值域或夹具任何一处变动都会让它红，
    /// 逼着改动者重新确认区分力，而不是让 fuzz 悄悄退化成纸面覆盖。
    private static final int MIDDLE_SEED_FLOOR = 46;

    @Override
    protected List<FixtureRow> fixtureRows() {
        return Fixtures.OPERATOR_ROWS;
    }

    @TestFactory
    Stream<DynamicTest> everySeededTreeMatchesMemoryOracle() {
        return LongStream.range(0, SEED_COUNT).mapToObj(seed -> {
            Tree.And tree = Generator.forSeed(seed);
            return DynamicTest.dynamicTest("seed=" + seed + ": " + tree.render(), () -> assertTree(seed, tree));
        });
    }

    /// fuzz 的区分力守卫：期望值永远空集或永远全表的生成器等于没测。
    ///
    /// 这条断言只看内存一侧（不查库），所以它便宜且与后端无关。
    @Test
    void enoughSeedsReachTheMiddle() {
        List<FixtureRow> rows = fixtureRows();
        long total = rows.size();
        List<Integer> hits = LongStream.range(0, SEED_COUNT)
                .mapToObj(
                        seed -> Memory.matchedIds(Generator.forSeed(seed), rows).size())
                .toList();
        long middle = hits.stream().filter(hit -> hit > 0 && hit < total).count();
        assertThat(middle)
                .as("中间态 seed 数（下限 %s/%s）；各 seed 命中行数=%s", MIDDLE_SEED_FLOOR, SEED_COUNT, hits)
                .isGreaterThanOrEqualTo(MIDDLE_SEED_FLOOR);
    }

    /// 逐元素比对，不是集合比对：排序错了也要红。
    private void assertTree(long seed, Tree.And tree) {
        List<Long> expected = toMemory(tree);
        List<Long> actual = toBackend(tree);
        assertThat(actual).as("seed=%d %s", seed, tree.render()).containsExactlyElementsOf(expected);
    }

    /// 后端渲染：概念树 → 真库返回的 id 列表（升序）。只允许读概念树。
    private List<Long> toBackend(Tree.And tree) {
        return new Backend<>(this).ids(tree);
    }

    /// 内存渲染：概念树 → 期望的 id 列表。只允许读概念树与 `fixtureRows()`，不碰任何生产代码。
    private List<Long> toMemory(Tree.And tree) {
        return Memory.matchedIds(tree, fixtureRows());
    }
}
