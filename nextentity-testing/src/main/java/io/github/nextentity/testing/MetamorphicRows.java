package io.github.nextentity.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.Function;

/// 变形用例（TLP / 变形关系）的**确定性**行生成器：固定种子 → 每次运行数据完全相同，失败可复现。
///
/// 为什么不用手写的常量表：TLP（三值逻辑分区）要求被分区列上**同时**存在
/// 「大于边界」「小于边界」「`null`」三类行，而且基数要够大。分区一旦退化成空集或全集，
/// 等式两侧会同时成立，等式就失去了杀伤力——手写十几行既难读，又会在换边界后悄悄失效。
/// 固定种子生成既确定、又可调，还能顺带把「边界值恰好不存在」这种退化情形挡在门外
/// （边界一律从夹具里取**实际存在的值**，见 {@link #boundary}）。
///
/// 刻意不接 `Clock` / 全局随机源：种子是本文件的常量，不读任何环境状态
/// （项目红线 `NO_AMBIENT_CLOCK_OR_RANDOM` 管的是「直调 `Instant.now()` / `new Random()`」，
/// 定种子不是环境随机）。
public final class MetamorphicRows {

    /// 固定种子：改它等于换一套夹具，所有从夹具取边界的用例会跟着变（不需要改用例本身）。
    public static final long SEED = 20261025L;

    /// 默认行数：足够让每个分区桶非空，又不至于把落库用例拖慢到不可接受。
    public static final int DEFAULT_COUNT = 64;

    private static final String[] NAMES = {"apple", "apple pie", "banana", "cherry", "durian"};

    private static final String[] NOTES = {"n1", "n2", "n3"};

    private MetamorphicRows() {}

    /// 默认夹具（{@link #DEFAULT_COUNT} 行）。
    ///
    /// @return 按 id 升序的行，`score` / `score2` / `note` 各有约五分之一为 `null`
    public static List<FixtureRow> rows() {
        return rows(DEFAULT_COUNT);
    }

    /// 生成指定行数的夹具。
    ///
    /// @param count 行数，必须为正
    /// @return 按 id 升序的行，id 从 1 连续编号
    public static List<FixtureRow> rows(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("行数必须为正: " + count);
        }
        ItemStatus[] statuses = ItemStatus.values();
        Random random = new Random(SEED);
        List<FixtureRow> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = NAMES[random.nextInt(NAMES.length)];
            Integer score = random.nextInt(5) == 0 ? null : random.nextInt(100);
            Integer score2 = random.nextInt(5) == 0 ? null : random.nextInt(100);
            String note = random.nextInt(3) == 0 ? null : NOTES[random.nextInt(NOTES.length)];
            ItemStatus status = statuses[random.nextInt(statuses.length)];
            rows.add(new FixtureRow(i + 1L, name, score, score2, note, status));
        }
        return rows;
    }

    /// 从夹具里取一个**实际存在**的列值作为分区边界（取中位数）。
    ///
    /// 边界必须是真实值，否则「`score > 边界` 与 `score < 边界` 少算一行」这类错位
    /// 可能因为夹具里根本没有 `score == 边界` 的行而无法暴露（自我检查用例会因此假通过）。
    /// 取中位数而不是最小值/最大值，是为了让两个非空桶的大小接近，避免退化成空集或全集。
    ///
    /// @param rows   夹具行
    /// @param column 取哪一列
    /// @return 该列非 `null` 值的中位数；该列全为 `null` 时抛 {@link IllegalArgumentException}
    public static int boundary(List<FixtureRow> rows, Function<FixtureRow, Integer> column) {
        int[] values = rows.stream()
                .map(column)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sorted()
                .toArray();
        if (values.length == 0) {
            throw new IllegalArgumentException("该列全为 null，无法取分区边界");
        }
        return values[values.length / 2];
    }
}
