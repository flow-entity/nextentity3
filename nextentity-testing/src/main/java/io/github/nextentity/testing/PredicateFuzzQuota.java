package io.github.nextentity.testing;

import java.util.List;

/// 覆盖配额：把「每种算子 × 每一列」逐个点到的 seed 表。
///
/// 随机形状能证明的东西有上限——它只能证明「撞到过的组合是对的」。上一版生成器里 `In` 作用在
/// 文本列、`IsNull` 作用在枚举列上**一次都没生成**，对应的后端渲染分支因此从没执行过
/// （合并口径的 JaCoCo 报告指出的正是这两处）。随机抽取永远无法保证「每个组合至少出现一次」，
/// 所以这里改用**固定的 (列, 算子) 表**：一格一个组合，把随机形状点不到、或只能靠运气点到的
/// 组合全部补上。
///
/// 表里有两格在夹具上**恒为空集**（`IsNull(name)`、`IsNull(status)`——这两列永不为 `null`）：
/// 它们不是凑数，`IS NULL` 落在非空列上仍然要渲染出一条 SQL，那个分支必须被执行；区分力由
/// 另外 13 格与 60 个随机 seed 负责，见 `PredicateFuzzDifferentialTest#enoughSeedsReachTheMiddle`。
///
/// 表写成静态常量而不是 `switch`：15 个分支的 `switch` 会顶到 PMD 的 `CyclomaticComplexity`
/// （每方法上限 10），而且一格一行的表比 15 个 `case` 更好核对。
final class PredicateFuzzQuota {

    /// 一格一个「算子 × 列」组合，逐格覆盖 [Column.Kind] 里每种算子在它适用的每一列上的组合。
    private static final List<Tree> CELLS = List.of(
            // `In` 在文本列上——早先这一分支从未被渲染过。
            new Tree.In(Column.Text.NAME, List.of(new Value.Text("apple"), new Value.Text("banana"))),
            new Tree.In(Column.Text.NOTE, List.of(new Value.Text("n1"), new Value.Text("n2"))),
            // `In` / `IsNull` 在枚举列上。
            new Tree.In(Column.Status.STATUS, List.of(new Value.Status(ItemStatus.ACTIVE))),
            new Tree.IsNull(Column.Status.STATUS),
            // `IsNull` 在非空文本列上（恒空集，但分支要执行）。
            new Tree.IsNull(Column.Text.NAME),
            // `Like` 的三种形态各一格（`likePattern` 的三个分支因此是确定性覆盖）。
            new Tree.Like(Column.Text.NAME, Generator.likePattern("app", 0)),
            new Tree.Like(Column.Text.NOTE, Generator.likePattern("2", 1)),
            new Tree.Like(Column.Text.NAME, Generator.likePattern("pie", 2)),
            // `Contains` 在两列上，取中间命中的片段（非前缀）。
            new Tree.Contains(Column.Text.NAME, "nana"),
            new Tree.Contains(Column.Text.NOTE, "1"),
            // `Eq` 在枚举列上。
            new Tree.Eq(Column.Status.STATUS, new Value.Status(ItemStatus.NEW)),
            // `IsNull` 在可空文本列上。
            new Tree.IsNull(Column.Text.NOTE),
            // `In` / `IsNull` / `Range` 在数值列上。
            new Tree.In(Column.Number.SCORE, List.of(new Value.Number(10), new Value.Number(30))),
            new Tree.IsNull(Column.Number.SCORE2),
            new Tree.Range(Column.Number.SCORE2, 3, 10));

    /// 配额格数（= 配额 seed 数）。
    static final int SIZE = CELLS.size();

    private PredicateFuzzQuota() {}

    /// @param seed 配额 seed
    /// @return 单元素的合取链（等价于该格条件本身）
    static Tree.And forSeed(long seed) {
        return new Tree.And(List.of(CELLS.get((int) Math.floorMod(seed, SIZE))));
    }
}
