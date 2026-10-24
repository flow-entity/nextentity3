package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
    /// 实测 60 个 seed 里有 46 个落在中间态（12 个空集、2 个全表）：四种形状各 15 个，
    /// 空集主要来自「组合子 + 另一列叶子」这种合取形状——6 行夹具上两个条件不相交是常态。
    /// 下限钉在实测值上（46，不是打折后的数）：生成器、取值域或夹具任何一处变动都会让它红，
    /// 逼着改动者重新确认区分力，而不是让 fuzz 悄悄退化成纸面覆盖。
    private static final int MIDDLE_SEED_FLOOR = 46;

    /// 字符串算子叶子（`Contains` / `Like`）在 60 棵树里出现次数的下限。
    ///
    /// 这两种叶子是**唯一**能钉住「`contains(v)` 渲染成 `%v%` 还是 `v%`」的节点，抽不到就等于没测。
    /// 上一轮的取值域几乎全是前缀（`app`、`n`），60 个 seed 恰好没抽到非前缀子串，故障注入实验 #10
    /// 因此从 fuzz 里漏了过去——靠抽签不算覆盖。现在形状 3 用固定配额保证每轮都抽到（见
    /// `Generator#stringSeed`），这条下限守的是「配额还在」。
    ///
    /// 计数盘面是 60 棵随机树 + 15 棵配额树（见 `allTrees`）：配额树是确定性的，把它们排除在外
    /// 会让这条守卫随随机形状漂移，反而更容易假绿。实测 13 个 `Contains`（形状 3 的 8 个专用
    /// seed + 配额表的 2 个 + 随机形状的 3 个）、13 个 `Like`（7 个专用 + 配额表 3 个 + 随机 3 个）。
    /// 下限钉在**实测值**上：生成器、取值域或形状分布任何一处变动都会让它红，逼着改动者重新确认。
    ///
    /// 随机那部分里 `Contains` 的密度从 1/4 降到 1/5，是因为文本列的算子池新增了 `In`
    /// （从 4 种变 5 种）——那是「每列每种算子都要能生成」的代价，用配额树补回绝对数量，门槛不降。
    private static final int CONTAINS_NODE_FLOOR = 13;

    /// 同上，`Like` 叶子的出现次数下限：实测 13 个（盘面同上）。
    private static final int LIKE_NODE_FLOOR = 13;

    /// 配额 seed 的起始编号。配额 seed 与随机 seed 分开编号，失败信息里一眼能看出是哪一类。
    private static final long QUOTA_SEED_BASE = 100;

    /// 能被「把 `contains` 退化成前缀匹配」区分出来的 seed 数下限。
    ///
    /// 比「节点出现次数」更硬的守卫：光抽到字符串叶子还不够，还要抽到**非前缀**取值
    /// （`ppl` / `pie` / `nana` 之类），否则 `%v%` 与 `v%` 在夹具上同解，差分看不见缺陷。
    /// 实现是把同一棵树里每个 `Contains(c,v)` 换成 `Like(c,v + "%")` 再算一遍期望值，两边不同的
    /// seed 才算有区分力——两次求值都只走 [Memory]，不碰后端，也不碰生产代码。
    ///
    /// 实测 8 个（`seed=[6,7,11,15,19,23,27,40]`）；取值域换成非前缀子串之前只有 2 个。
    private static final int PREFIX_BLIND_SEED_FLOOR = 8;

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

    /// 覆盖配额用例：15 个 seed 逐个点到一个「算子 × 列」组合（见 [PredicateFuzzQuota]）。
    ///
    /// 与随机 seed 走**同一条差分路径**（同一棵概念树 → 两个渲染器），区别只在树的来源是固定的表。
    @TestFactory
    Stream<DynamicTest> everyQuotaTreeMatchesMemoryOracle() {
        return LongStream.range(QUOTA_SEED_BASE, QUOTA_SEED_BASE + PredicateFuzzQuota.SIZE)
                .mapToObj(seed -> {
                    Tree.And tree = PredicateFuzzQuota.forSeed(seed);
                    return DynamicTest.dynamicTest(
                            "quota seed=" + seed + ": " + tree.render(), () -> assertTree(seed, tree));
                });
    }

    /// 生成器覆盖守卫：每个「算子 × 列」组合都必须至少生成过一次。
    ///
    /// 这是把覆盖率报告事后才能发现的问题挪到事前：上一版生成器里 `In` 在文本列、`IsNull` 在
    /// 枚举列上从没生成，后端那两个渲染分支因此从没被执行过。现在缺一个组合就红，且**不靠
    /// 随机**——随机 seed 点不到的由 [PredicateFuzzQuota] 的固定表兜底。
    @Test
    void everyApplicableColumnAndKindIsGenerated() {
        Set<String> generated = PredicateFuzzCoverage.pairs(allTrees());
        List<String> missing = new ArrayList<>();
        for (Column column : Column.all()) {
            for (Column.Kind kind : Column.Kind.values()) {
                String key = PredicateFuzzCoverage.key(kind, column);
                if (kind.appliesTo(column) && !generated.contains(key)) {
                    missing.add(key);
                }
            }
        }
        assertThat(missing).as("每种算子都要在它适用的每一列上生成过；未生成=%s", missing).isEmpty();
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

    /// 字符串算子的配额守卫：`Contains` / `Like` 每轮都必须被抽到足够多次。
    ///
    /// 只数合取链的**直接**元素就够了：`Contains` / `Like` 实现的是 [Tree] 而不是 `Tree.Scalar`，
    /// 而 `Or` / `Not` 只吃 `Scalar`，所以字符串叶子在结构上只可能挂在合取链顶层，
    /// 不会被组合子藏起来。
    @Test
    void stringOperatorNodesAreDrawnOftenEnough() {
        long contains = 0;
        long like = 0;
        for (Tree.And tree : allTrees()) {
            for (Tree part : tree.parts()) {
                if (part instanceof Tree.Contains) {
                    contains++;
                } else if (part instanceof Tree.Like) {
                    like++;
                }
            }
        }
        assertThat(contains)
                .as(
                        "Contains 叶子出现次数（下限 %s；树总数 %s）；实测 %s",
                        CONTAINS_NODE_FLOOR, allTrees().size(), contains)
                .isGreaterThanOrEqualTo(CONTAINS_NODE_FLOOR);
        assertThat(like)
                .as(
                        "Like 叶子出现次数（下限 %s；树总数 %s）；实测 %s",
                        LIKE_NODE_FLOOR, allTrees().size(), like)
                .isGreaterThanOrEqualTo(LIKE_NODE_FLOOR);
    }

    /// 「非前缀取值真的被抽到了」的守卫：把 `contains` 当成前缀匹配之后，期望值必须变。
    ///
    /// 这条是故障注入实验 #10 的直接产物：当时 60 个 seed 全绿，但把 `contains` 的通配符改错也
    /// 全绿——因为夹具里所有名字都以 `app` 开头，`%app%` 与 `app%` 同解。现在要求**至少
    /// [PREFIX_BLIND_SEED_FLOOR] 个 seed 在退化前后给出不同的期望值**，靠运气通过就不可能。
    @Test
    void containsPatternsAreDiscriminatedByFixedSeeds() {
        List<FixtureRow> rows = fixtureRows();
        List<Long> discriminating = LongStream.range(0, SEED_COUNT)
                .filter(seed -> {
                    Tree.And tree = Generator.forSeed(seed);
                    return !Memory.matchedIds(tree, rows).equals(Memory.matchedIds(prefixBlind(tree), rows));
                })
                .boxed()
                .toList();
        assertThat(discriminating.size())
                .as(
                        "能区分「contains 退化成前缀匹配」的 seed 数（下限 %s；seed 总数 %s）；这些 seed=%s",
                        PREFIX_BLIND_SEED_FLOOR, SEED_COUNT, discriminating)
                .isGreaterThanOrEqualTo(PREFIX_BLIND_SEED_FLOOR);
    }

    /// 把每个 `Contains(c,v)` 换成 `Like(c, v + "%")`，模拟「`contains` 被写成前缀匹配」的退化。
    ///
    /// @param tree 原始概念树
    /// @return 退化后的概念树
    private static Tree.And prefixBlind(Tree.And tree) {
        // 单个节点退化：`Contains` → 前缀 `Like`，其余原样（`Or` / `Not` 里不可能有字符串叶子）。
        return new Tree.And(tree.parts().stream()
                .map(part -> part instanceof Tree.Contains contains
                        ? (Tree) new Tree.Like(contains.column(), contains.text() + "%")
                        : part)
                .toList());
    }

    /// 守卫用的统一盘面：60 棵随机树 + [PredicateFuzzQuota#SIZE] 棵配额树。
    ///
    /// 覆盖守卫与节点计数守卫都看这一个盘面：配额树是**确定性**的（谁点哪个组合写在表里），
    /// 把它们排除在外会让「字符串叶子够不够」随随机形状漂移，反而更容易假绿。
    private static List<Tree.And> allTrees() {
        List<Tree.And> trees = new ArrayList<>();
        LongStream.range(0, SEED_COUNT).forEach(seed -> trees.add(Generator.forSeed(seed)));
        LongStream.range(QUOTA_SEED_BASE, QUOTA_SEED_BASE + PredicateFuzzQuota.SIZE)
                .forEach(seed -> trees.add(PredicateFuzzQuota.forSeed(seed)));
        return trees;
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
