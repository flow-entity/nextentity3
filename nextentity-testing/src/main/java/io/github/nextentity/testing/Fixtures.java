package io.github.nextentity.testing;

import java.util.List;
import java.util.stream.Stream;

/// 夹具原始数据的唯一出处：写进后端的行与内存 oracle 读的行是**同一份**数据。
///
/// 两份数据一旦分家，差分就退化成「两个手算常量互相比」，也就失去了「独立算期望值」的意义。
public final class Fixtures {

    /// 标准夹具（四行，按 id 升序）：所有一致性基类默认使用，全部断言按这份数据手算。
    ///
    /// ```text
    /// id=1 name=apple     score=10   score2=10   note=null status=NEW
    /// id=2 name=banana    score=20   score2=10   note=n1   status=ACTIVE
    /// id=3 name=cherry    score=30   score2=null note=n2   status=DONE
    /// id=4 name=apple pie score=null score2=5    note=null status=NEW
    /// ```
    public static final List<FixtureRow> ITEM_ROWS = List.of(
            new FixtureRow(1L, "apple", 10, 10, null, ItemStatus.NEW),
            new FixtureRow(2L, "banana", 20, 10, "n1", ItemStatus.ACTIVE),
            new FixtureRow(3L, "cherry", 30, null, "n2", ItemStatus.DONE),
            new FixtureRow(4L, "apple pie", null, 5, null, ItemStatus.NEW));

    /// 算子矩阵夹具（六行，按 id 升序）：列对列比较与表达式算术的差分用例专用。
    ///
    /// ```text
    /// id=1 score=10   score2=10
    /// id=2 score=20   score2=10
    /// id=3 score=30   score2=null
    /// id=4 score=null score2=5
    /// id=5 score=5    score2=25
    /// id=6 score=-4   score2=3
    /// ```
    ///
    /// 就是 {@link #ITEM_ROWS} 再追加 id=5 / id=6 两行，两处追加都是刻意的：
    /// - 标准夹具里没有一行 `score < score2`，`lt` 只能命中空集——那种「恒假」用例
    ///   在渲染写错时照样通过（上一轮 `between` 区间恰好等于全表就吃过这个亏）；
    /// - id=6 的负数是算术矩阵的关键数据：`sub` / `div` / `mod` 的操作数顺序与截断方向
    ///   只有「负数 + 不整除」的组合才切得开。
    public static final List<FixtureRow> OPERATOR_ROWS = Stream.concat(
                    ITEM_ROWS.stream(),
                    Stream.of(
                            new FixtureRow(5L, "damson", 5, 25, null, ItemStatus.NEW),
                            new FixtureRow(6L, "elderberry", -4, 3, null, ItemStatus.NEW)))
            .toList();

    private Fixtures() {}
}
