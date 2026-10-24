package io.github.nextentity.testing;

import java.util.List;

/// 内存渲染器：概念树 → 期望的 id 列表。**只读概念树与夹具**，不看 [Backend]，
/// 也不调用 nextentity 的任何生产代码。
///
/// 行数据由调用方按 `fixtureRows()` 传进来，不在这里硬编码夹具常量：子类覆写 `fixtureRows()` 时，
/// 灌进库的行与这里求值的行必须是同一份，否则差分静默假绿；独立性只约束**求值逻辑**。
///
/// 这里自己实现 SQL 的三值逻辑（Kleene）：`UNKNOWN` 会传染，只有结果为 `TRUE` 的行才命中。
/// 夹具里 `score` 为 `null` 的那一行因此既不满足 `=`、也不满足 `<>`，`BETWEEN` 两侧都不进，
/// `NOT UNKNOWN` 仍然是 `UNKNOWN`——这些正是「手算常量」最容易写错的地方。
final class Memory {

    private Memory() {}

    static List<Long> matchedIds(Tree.And tree, List<FixtureRow> rows) {
        return rows.stream()
                .filter(row -> tree.parts().stream().allMatch(part -> eval(part, row) == Truth.TRUE))
                .map(FixtureRow::id)
                .toList();
    }

    private static Truth eval(Tree tree, FixtureRow row) {
        return switch (tree) {
            case Tree.Eq node -> eq(node, row);
            case Tree.Range node -> range(node, row);
            case Tree.In node -> in(node, row);
            case Tree.IsNull node -> Truth.of(columnValue(node.column(), row) == null);
            case Tree.Contains node -> contains(node, row);
            case Tree.Like node -> like(node, row);
            case Tree.Or node -> eval(node.left(), row).or(eval(node.right(), row));
            case Tree.Not node -> eval(node.child(), row).negate();
        };
    }

    private static Truth eq(Tree.Eq node, FixtureRow row) {
        Object actual = columnValue(node.column(), row);
        if (actual == null) {
            return Truth.UNKNOWN;
        }
        return Truth.of(actual.equals(raw(node.value())));
    }

    private static Truth range(Tree.Range node, FixtureRow row) {
        Object actual = columnValue(node.column(), row);
        if (actual instanceof Integer value) {
            return Truth.of(value >= node.from() && value <= node.to());
        }
        return Truth.UNKNOWN;
    }

    private static Truth in(Tree.In node, FixtureRow row) {
        Object actual = columnValue(node.column(), row);
        if (actual == null) {
            return Truth.UNKNOWN;
        }
        return Truth.of(node.values().stream().map(Memory::raw).anyMatch(actual::equals));
    }

    private static Truth contains(Tree.Contains node, FixtureRow row) {
        Object actual = columnValue(node.column(), row);
        if (actual instanceof String value) {
            return Truth.of(value.contains(node.text()));
        }
        return Truth.UNKNOWN;
    }

    private static Truth like(Tree.Like node, FixtureRow row) {
        Object actual = columnValue(node.column(), row);
        if (actual instanceof String value) {
            return Truth.of(likeMatches(value, node.pattern()));
        }
        return Truth.UNKNOWN;
    }

    /// `LIKE` 的独立实现：生成器只产出 `值%` / `%值` / `%值%` 三种形态，因此只需前缀 /
    /// 后缀 / 包含三种判定，不用正则，也不看后端把 `LIKE` 渲染成什么。
    private static boolean likeMatches(String actual, String pattern) {
        if (pattern.startsWith("%") && pattern.endsWith("%") && pattern.length() > 1) {
            return actual.contains(pattern.substring(1, pattern.length() - 1));
        }
        if (pattern.startsWith("%")) {
            return actual.endsWith(pattern.substring(1));
        }
        return actual.startsWith(pattern.substring(0, pattern.length() - 1));
    }

    private static Object columnValue(Column column, FixtureRow row) {
        return switch (column) {
            case Column.Number number ->
                switch (number) {
                    case SCORE -> row.score();
                    case SCORE2 -> row.score2();
                };
            case Column.Text text ->
                switch (text) {
                    case NOTE -> row.note();
                    case NAME -> row.name();
                };
            case Column.Status status -> row.status();
        };
    }

    private static Object raw(Value value) {
        return switch (value) {
            case Value.Number number -> number.value();
            case Value.Text text -> text.text();
            case Value.Status status -> status.status();
        };
    }

    /// SQL 的三值逻辑：`UNKNOWN` 参与的比较结果也是 `UNKNOWN`。
    private enum Truth {
        TRUE,
        FALSE,
        UNKNOWN;

        static Truth of(boolean holds) {
            return holds ? TRUE : FALSE;
        }

        Truth or(Truth other) {
            if (this == TRUE || other == TRUE) {
                return TRUE;
            }
            return this == UNKNOWN || other == UNKNOWN ? UNKNOWN : FALSE;
        }

        Truth negate() {
            return switch (this) {
                case TRUE -> FALSE;
                case FALSE -> TRUE;
                case UNKNOWN -> UNKNOWN;
            };
        }
    }
}
