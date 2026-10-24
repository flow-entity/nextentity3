package io.github.nextentity.testing;

import java.util.ArrayList;
import java.util.List;

/// 概念树：与两种实现都无关的谓词描述。
///
/// 三个子族：
///
/// - [Scalar]：能作为一个布尔表达式参与 `and`/`or`/`not` 的条件（比较、集合、空值）；
/// - [Contains] / [Like]：字符串匹配叶子；
/// - [And]：**合取链**，只出现在根位置，元素可以是上面任意一种。
///
/// 为什么合取链不能嵌进 `or`/`not`：3.x 的根级字符串字段只在 `where(StringRef).contains(...)`
/// 这类**链式算子**上暴露匹配能力，链式算子产出的不是 `Expression`（`api/Path.java` 的
/// `Path` 只带比较/集合/空值/区间算子，`StringOps` 长在 `StringPath` 上，而 `StringPath`
/// 只能经 `EntityPath#get(StringRef)` 取得，那必然追加一段路径）。所以「根级字符串字段参与
/// 析取/取反」在当前 API 上**不可表达**；把它写进概念树的类型里，而不是在渲染器里抛异常。
sealed interface Tree {

    /// 规范串：用例名与失败信息都用它，一眼看出是哪棵树。
    String render();

    /// 标量条件：可作为一个 `Expression` 使用的叶子或组合。
    sealed interface Scalar extends Tree {}

    /// 相等（三值：任一侧为 `NULL` 时结果为 `UNKNOWN`）。
    record Eq(Column column, Value value) implements Scalar {

        @Override
        public String render() {
            return "Eq(" + column.label() + "," + value.render() + ")";
        }
    }

    /// 闭区间（`BETWEEN`，两端都含；`NULL` 行两侧都不命中）。
    record Range(Column.Number column, int from, int to) implements Scalar {

        @Override
        public String render() {
            return "Range(" + column.label() + "," + from + "," + to + ")";
        }
    }

    /// 集合成员（`IN`；`NULL` 行的结果是 `UNKNOWN`）。
    record In(Column column, List<Value> values) implements Scalar {

        @Override
        public String render() {
            return "In(" + column.label() + ",[" + join() + "])";
        }

        private String join() {
            return String.join(",", values.stream().map(Value::render).toList());
        }
    }

    /// 空值判定（两值：`IS NULL` 永不为 `UNKNOWN`）。
    record IsNull(Column column) implements Scalar {

        @Override
        public String render() {
            return "IsNull(" + column.label() + ")";
        }
    }

    /// 逻辑或（Kleene：`TRUE or UNKNOWN = TRUE`）。
    record Or(Scalar left, Scalar right) implements Scalar {

        @Override
        public String render() {
            return "Or(" + left.render() + "," + right.render() + ")";
        }
    }

    /// 逻辑非（Kleene：`NOT UNKNOWN = UNKNOWN`）。
    record Not(Scalar child) implements Scalar {

        @Override
        public String render() {
            return "Not(" + child.render() + ")";
        }
    }

    /// 子串包含（`NULL` 行为 `UNKNOWN`）。
    record Contains(Column.Text column, String text) implements Tree {

        @Override
        public String render() {
            return "Contains(" + column.label() + ",'" + text + "')";
        }
    }

    /// 模式匹配，只生成前缀/后缀/包含三种形态（见 [Generator]）。
    record Like(Column.Text column, String pattern) implements Tree {

        @Override
        public String render() {
            return "Like(" + column.label() + ",'" + pattern + "')";
        }
    }

    /// 合取链：元素全为真才命中。**不是** [Tree] 的子类型——见类型注释。
    record And(List<Tree> parts) {

        /// 单片合取直接渲染成那一片，免得用例名里出现无意义的 `And(x)`。
        public String render() {
            if (parts.size() == 1) {
                return parts.get(0).render();
            }
            return "And(" + String.join(",", parts.stream().map(Tree::render).toList()) + ")";
        }
    }
}

/// 概念树里的列：按值类型分成三族，渲染器因此不必猜「这个值能不能直接比较」。
sealed interface Column {

    /// 渲染用的列名。
    String label();

    /// 该列在夹具里是否有 `null` 单元格。
    ///
    /// 这是**夹具**的属性，不是实现的属性：可空列才生成 `IsNull` 叶子。对永不为 `null` 的列
    /// 生成 `IS NULL` 只会得到空集，白占一个 seed（fuzz 的区分力就是这么被浪费掉的）。
    boolean nullable();

    /// 数值列（两列都可空）。
    enum Number implements Column {
        SCORE("score", true),
        SCORE2("score2", true);

        private final String label;

        private final boolean nullable;

        Number(String label, boolean nullable) {
            this.label = label;
            this.nullable = nullable;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public boolean nullable() {
            return nullable;
        }
    }

    /// 文本列：`note` 可空，`name` 非空。
    enum Text implements Column {
        NOTE("note", true),
        NAME("name", false);

        private final String label;

        private final boolean nullable;

        Text(String label, boolean nullable) {
            this.label = label;
            this.nullable = nullable;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public boolean nullable() {
            return nullable;
        }
    }

    /// 枚举列。夹具里只有 `status` 一个，单常量枚举换来的是「叶子取值域」的类型约束。
    enum Status implements Column {
        STATUS("status", false);

        private final String label;

        private final boolean nullable;

        Status(String label, boolean nullable) {
            this.label = label;
            this.nullable = nullable;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public boolean nullable() {
            return nullable;
        }
    }
}

/// 概念树里的取值：类型跟着列走。
sealed interface Value {

    /// 渲染用的字面量形态。
    String render();

    /// 整数取值。
    record Number(int value) implements Value {

        @Override
        public String render() {
            return Integer.toString(value);
        }
    }

    /// 文本取值。
    record Text(String text) implements Value {

        @Override
        public String render() {
            return "'" + text + "'";
        }
    }

    /// 枚举取值。
    record Status(ItemStatus status) implements Value {

        @Override
        public String render() {
            return status.name();
        }
    }
}
/// 概念树生成器：**只依赖 seed**，不看任何一侧的渲染器。
///
/// 形状由 `seed % 4` 决定，60 个 seed 因此恰好 15/15/15/15：
///
/// - 形状 0：单叶——纯谓词，把每种叶算子单独钉一遍；
/// - 形状 1：整棵树就是一个组合子——`Or(叶,叶)` / `Not(叶)` / `Or(叶,Not(叶))`，两片叶子强制不同列；
/// - 形状 2：组合 + 合取——`And(组合子,叶)`，第二片叶子避开组合子的主列；
/// - 形状 3：字符串算子固定配额——8 个 seed 是 `Contains`、7 个是 `Like`，子串轮转整个取值域
///   （见 `stringSeed`）。这一种形状是**结构保证**而不是抽签：`Contains` / `Like` 是唯一能钉住
///   「`contains(v)` 渲染成 `%v%` 还是 `v%`」的节点，靠随机形状去撞可能整批都撞不到非前缀子串。
///
/// 早先的形状 1 是「两片不同列的叶直接合取」，实测只有 37/60 落在中间态：6 行夹具上随机两个
/// 独立条件极少相交，大批 seed 白烧成空集。改成「析取为主」之后是 46/60——区分力是生成器的
/// 设计指标，不是事后统计出来的装饰。
///
/// 用 `seed % 4` 而不是从随机流里挑形状，是为了保证每种形状都恰好占四分之一：纯随机可能
/// 整批都落在同一种形状上，那 fuzz 就退化了。叶算子再按列族细分（数值列 4 种、文本列 4 种、
/// 枚举列 3 种），取值域在 [Generator] 的常量里列全，`IN` 取 1..3 个值且不生成空集合
/// （空集合是常量折叠，已经在手算常量层钉过）。
final class Generator {

    /// 数值取值域按列分开，且**以夹具里真实出现的值为底**：这样 `Eq`/`In`/`Range` 大多能命中，
    /// 期望值落在中间态而不是空集；每列各留一个「夹具里不存在的值」（`0`）专门生成空集，
    /// 空集也是要覆盖的结果。含负数（`score=-4`）是刻意的：负数边界最容易写错。
    private static final int[] SCORE_NUMBERS = {-4, 0, 5, 10, 20, 30};

    private static final int[] SCORE2_NUMBERS = {0, 3, 5, 10, 25};

    /// 文本取值域同样按列分开：`name` 是英文名、`note` 是短标记，混用会让一半 seed 落在空集上
    /// （`Eq(note,'apple')` 永远不命中），fuzz 的区分力就被浪费了。全小写是刻意的：
    /// MySQL 默认排序规则大小写不敏感、H2 大小写敏感，用大小写混合的取值会造出方言差异。
    /// 同一串同时出现在「完整取值域」与「子串取值域」里，抽成常量让字面量在文件里只出现一次
    /// （PMD `AvoidDuplicateLiterals` 的上限是 1 次）。
    private static final String BERRY = "berry";

    private static final String[] NAME_TEXTS = {"apple", "app", "banana", BERRY, "apple pie", "damson", "zzz"};

    private static final String[] NOTE_TEXTS = {"n1", "n2", "n", "1", "zzz", ""};

    /// `Contains` / `Like` 专用的**子串**取值域：不能复用上面的完整取值，因为那里几乎全是前缀
    /// （`apple`、`app`、`n`），把 `contains` 误写成前缀匹配时 `%v%` 与 `v%` 在夹具上同解，差分
    /// 看不见缺陷（故障注入实验 #10 就漏在这一格）。这里刻意放入中间命中（`ppl`、`nana`、`ana`）、
    /// 后缀命中（`pie`、`berr`）与零命中（`zzz`）三类样本。
    private static final String[] NAME_FRAGMENTS = {"app", "ppl", "pie", "nana", "ana", BERRY, "berr", "zzz"};

    private static final String[] NOTE_FRAGMENTS = {"n", "1", "2", "n1", "zzz"};

    private static final Column[] COLUMNS = {
        Column.Number.SCORE, Column.Number.SCORE2, Column.Text.NOTE, Column.Text.NAME, Column.Status.STATUS
    };

    private Generator() {}

    /// 四种形状各占 1/4：单叶、整棵树就是一个组合子、组合子 + 另一列上的叶子、字符串算子固定配额。
    /// 组合子内部两片叶子落在不同列上（见 [composite]），形状 2 的第二个叶子也避开组合子的主列。
    static Tree.And forSeed(long seed) {
        Rng rng = new Rng(seed);
        return switch ((int) Math.floorMod(seed, 4)) {
            case 0 -> new Tree.And(List.of(leaf(rng, COLUMNS[rng.nextInt(COLUMNS.length)])));
            case 1 -> new Tree.And(List.of(composite(rng, COLUMNS[rng.nextInt(COLUMNS.length)])));
            case 2 -> {
                Column first = COLUMNS[rng.nextInt(COLUMNS.length)];
                yield new Tree.And(List.of(composite(rng, first), leaf(rng, other(rng, first))));
            }
            default -> stringSeed(seed);
        };
    }

    /// 形状 3：字符串算子的固定配额——15 个 seed 里 8 个是 `Contains`、7 个是 `Like`。
    ///
    /// 单列这一种形状的理由与叶算子无关，与**覆盖的确定性**有关：`Contains` / `Like` 是唯一能钉住
    /// 「`contains(v)` 渲染成 `%v%` 还是 `v%`」的节点，而靠随机形状去撞，可能整批 seed 都只抽到
    /// 前缀子串（上一轮就是这样漏过了故障注入实验 #10）。配额把「每个字符串算子被抽到几次」从
    /// 抽签变成结构保证：8 个 `Contains` 逐个吃掉 [NAME_FRAGMENTS] 的全部片段，7 个 `Like` 把
    /// 「前缀 / 后缀 / 包含」三种模式轮一遍。
    ///
    /// @param seed 种子
    /// @return 单元素的合取链（等价于该字符串条件本身）
    private static Tree.And stringSeed(long seed) {
        int index = (int) Math.floorMod(seed / 4, 15);
        String fragment = NAME_FRAGMENTS[Math.floorMod(index, NAME_FRAGMENTS.length)];
        if (index < 8) {
            return new Tree.And(List.of(new Tree.Contains(Column.Text.NAME, fragment)));
        }
        // 只出 `v%` 一种模式的话，非前缀子串（`ppl`、`nana`）会整齐地落成空集，白占 seed 的区分力。
        String pattern =
                switch (Math.floorMod(index, 3)) {
                    case 0 -> fragment + "%";
                    case 1 -> "%" + fragment;
                    default -> "%" + fragment + "%";
                };
        return new Tree.And(List.of(new Tree.Like(Column.Text.NAME, pattern)));
    }

    /// 组合子：`Or` / `Not` / `Or(·,Not(·))`，两片标量叶子落在不同列上。
    ///
    /// 两片落在不同列是有意的：同一列上随手拼两个条件很容易自相矛盾
    /// （`Not(Eq(status,ACTIVE))` 与 `In(status,[ACTIVE])` 拼起来必然空集），
    /// 那只是白烧一个 seed，不多测任何东西。
    private static Tree.Scalar composite(Rng rng, Column column) {
        Tree.Scalar first = scalar(rng, column);
        return switch (rng.nextInt(3)) {
            case 0 -> new Tree.Or(first, scalar(rng, other(rng, column)));
            case 1 -> new Tree.Not(first);
            default -> new Tree.Or(first, new Tree.Not(scalar(rng, other(rng, column))));
        };
    }

    /// 指定列的叶子（可含字符串匹配）。
    private static Tree leaf(Rng rng, Column column) {
        return switch (column) {
            case Column.Number number -> numberLeaf(rng, number);
            case Column.Text text -> textLeaf(rng, text);
            case Column.Status status -> statusLeaf(rng, status);
        };
    }

    /// 指定列的标量叶子——供 `or`/`not` 使用。
    private static Tree.Scalar scalar(Rng rng, Column column) {
        return switch (column) {
            case Column.Number number -> numberLeaf(rng, number);
            case Column.Text text ->
                switch (rng.nextInt(text.nullable() ? 2 : 1)) {
                    case 0 -> new Tree.Eq(text, new Value.Text(pickText(rng, text, false)));
                    default -> new Tree.IsNull(text);
                };
            case Column.Status status -> statusLeaf(rng, status);
        };
    }

    private static Tree.Scalar numberLeaf(Rng rng, Column.Number column) {
        int first = pickNumber(rng, column);
        int second = pickNumber(rng, column);
        return switch (rng.nextInt(4)) {
            case 0 -> new Tree.Eq(column, new Value.Number(first));
            case 1 -> new Tree.Range(column, Math.min(first, second), Math.max(first, second));
            case 2 -> {
                // `IN` 取 1..3 个值；取法是内联的，因为 `Generator` 的方法数卡在 PMD `TooManyMethods`
                // 的 12 上，多一个只被调用一次的私有方法就要挤掉别的东西。
                int count = 1 + rng.nextInt(3);
                List<Value> values = new ArrayList<>(count);
                for (int index = 0; index < count; index++) {
                    values.add(new Value.Number(pickNumber(rng, column)));
                }
                yield new Tree.In(column, List.copyOf(values));
            }
            default -> new Tree.IsNull(column);
        };
    }

    /// 文本叶子：`Eq` 取完整取值域，`Contains` / `Like` 取**子串**取值域（见 `pickText`）。
    ///
    /// 取值域必须跟着算子走，不能先抽值再挑算子：先抽的话，一半 seed 会拿完整取值去做
    /// `Contains`，命中与否取决于该取值是否是前缀——区分力只能靠运气。
    private static Tree textLeaf(Rng rng, Column.Text column) {
        return switch (rng.nextInt(column.nullable() ? 4 : 3)) {
            case 0 -> new Tree.Eq(column, new Value.Text(pickText(rng, column, false)));
            case 1 -> new Tree.Contains(column, pickText(rng, column, true));
            case 2 -> new Tree.Like(column, likePattern(rng, pickText(rng, column, true)));
            default -> new Tree.IsNull(column);
        };
    }

    /// 数值取值域跟着列走，见 [SCORE_NUMBERS] / [SCORE2_NUMBERS]。
    private static int pickNumber(Rng rng, Column.Number column) {
        int[] domain =
                switch (column) {
                    case SCORE -> SCORE_NUMBERS;
                    case SCORE2 -> SCORE2_NUMBERS;
                };
        return domain[rng.nextInt(domain.length)];
    }

    /// 文本取值域跟着列走：`note` 与 `name` 的取值集合不同，见 [NOTE_TEXTS] / [NAME_TEXTS]。
    ///
    /// `fragments` 为 `true` 时改用子串取值域（[NAME_FRAGMENTS] / [NOTE_FRAGMENTS]）：`Contains` /
    /// `Like` 要的是「能从串中间命中的片段」，`Eq` 要的是「夹具里真实存在的完整取值」，混用会让
    /// 一半 seed 白烧（`Eq(name, 'ppl')` 永远不命中，`Contains(name, 'apple')` 又退化成前缀）。
    ///
    /// @param rng       随机源
    /// @param column    列
    /// @param fragments 是否取子串片段而非完整取值
    /// @return 抽中的文本
    private static String pickText(Rng rng, Column.Text column, boolean fragments) {
        String[] domain =
                switch (column) {
                    case NOTE -> fragments ? NOTE_FRAGMENTS : NOTE_TEXTS;
                    case NAME -> fragments ? NAME_FRAGMENTS : NAME_TEXTS;
                };
        return domain[rng.nextInt(domain.length)];
    }

    private static Tree.Scalar statusLeaf(Rng rng, Column.Status column) {
        ItemStatus[] all = ItemStatus.values();
        ItemStatus first = all[rng.nextInt(all.length)];
        ItemStatus second = all[rng.nextInt(all.length)];
        return switch (rng.nextInt(column.nullable() ? 3 : 2)) {
            case 0 -> new Tree.Eq(column, new Value.Status(first));
            case 1 -> new Tree.In(column, List.of(new Value.Status(first), new Value.Status(second)));
            default -> new Tree.IsNull(column);
        };
    }

    /// `LIKE` 只生成前缀 / 后缀 / 包含三种形态，内存侧因此不必引正则。
    private static String likePattern(Rng rng, String text) {
        return switch (rng.nextInt(3)) {
            case 0 -> text + "%";
            case 1 -> "%" + text;
            default -> "%" + text + "%";
        };
    }

    /// 换一个与当前列不同的列（试 4 次仍相同就放弃，不影响正确性）。
    private static Column other(Rng rng, Column current) {
        Column other = COLUMNS[rng.nextInt(COLUMNS.length)];
        for (int attempt = 0; attempt < 4 && other == current; attempt++) {
            other = COLUMNS[rng.nextInt(COLUMNS.length)];
        }
        return other;
    }
}

/// 确定性伪随机源（SplitMix64）：**固定种子、可复现**，不碰 `new Random(seed)`。
///
/// `NO_AMBIENT_CLOCK_OR_RANDOM` 连带种子的 `Random` 构造器都禁，所以这里自带一个 6 行的
/// 混合函数——它只需要「同样的 seed 在两次运行里给出同样的序列」，不承担统计质量责任。
final class Rng {

    private static final long GAMMA = 0x9E3779B97F4A7C15L;

    private long state;

    Rng(long seed) {
        this.state = seed;
    }

    int nextInt(int bound) {
        state += GAMMA;
        long mixed = state;
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        return Math.floorMod(mixed, bound);
    }
}
