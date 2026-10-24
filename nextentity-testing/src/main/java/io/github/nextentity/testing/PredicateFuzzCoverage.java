package io.github.nextentity.testing;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/// 生成器覆盖自查：把一批概念树里出现过的「算子 × 列」组合收集起来。
///
/// 为什么需要它：覆盖率报告只能事后告诉你「某行没执行」，说不出「生成器从没生成过这个组合」。
/// 上一版生成器里 `In` 在文本列、`IsNull` 在枚举列上一次都没生成，后端那两个渲染分支因此
/// 从没执行过。这里把覆盖变成**事前断言**：每个适用组合都必须至少出现一次，缺一个就红。
///
/// 只读概念树，不碰后端也不碰生产代码。
final class PredicateFuzzCoverage {

    private PredicateFuzzCoverage() {}

    /// @param trees 概念树（合取链）列表
    /// @return 出现过的「算子 × 列」键集合
    static Set<String> pairs(List<Tree.And> trees) {
        Set<String> pairs = new TreeSet<>();
        for (Tree.And tree : trees) {
            for (Tree part : tree.parts()) {
                collect(part, pairs);
            }
        }
        return pairs;
    }

    /// @param kind   算子种类
    /// @param column 列
    /// @return 覆盖自查用的键
    static String key(Column.Kind kind, Column column) {
        return kind.name() + " × " + column.label();
    }

    private static void collect(Tree part, Set<String> pairs) {
        switch (part) {
            case Tree.Scalar scalar -> collectScalar(scalar, pairs);
            case Tree.Contains contains -> pairs.add(key(Column.Kind.CONTAINS, contains.column()));
            case Tree.Like like -> pairs.add(key(Column.Kind.LIKE, like.column()));
        }
    }

    private static void collectScalar(Tree.Scalar scalar, Set<String> pairs) {
        switch (scalar) {
            case Tree.Eq eq -> pairs.add(key(Column.Kind.EQ, eq.column()));
            case Tree.Range range -> pairs.add(key(Column.Kind.RANGE, range.column()));
            case Tree.In in -> pairs.add(key(Column.Kind.IN, in.column()));
            case Tree.IsNull isNull -> pairs.add(key(Column.Kind.IS_NULL, isNull.column()));
            case Tree.Or or -> {
                collectScalar(or.left(), pairs);
                collectScalar(or.right(), pairs);
            }
            case Tree.Not not -> collectScalar(not.child(), pairs);
        }
    }
}
