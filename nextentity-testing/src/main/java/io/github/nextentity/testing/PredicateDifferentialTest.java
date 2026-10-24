package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.BaseWhereStep;
import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/// 谓词（条件）矩阵的**差分** oracle：`eq` / `ne` / `gt` / `ge` / `lt` / `le` / `between` /
/// `notBetween` / `in` / `notIn` / `isNull` / `isNotNull` / `contains` / `like` / 枚举 `eq` /
/// `and` / `or` / `not`，加上条件化算子 `eqIfNotNull` / `eqIfNotEmpty` / `containsIfNotNull` /
/// `inIfNotNull`，共 27 格，每格一条动态用例。
///
/// 差分分工（两条路径不相交）：
/// - 真库一侧：`query().where(<后端谓词>).orderBy(id).asc()`，渲染走生产代码的谓词编译路径；
/// - 内存一侧：{@link Matrix} 里的三值逻辑直接读 {@link Fixtures#OPERATOR_ROWS} 的原始字段求值，
///   不调用 nextentity 的任何生产代码，也不回读后端结果。
///
/// 比对是**逐元素**的（`containsExactlyElementsOf`），不是集合相等：行集一样但顺序错，同样要红。
///
/// 内存一侧自己实现 SQL 三值逻辑（`TRUE` / `FALSE` / `UNKNOWN`），这是这个 oracle 的全部意义：
/// - 列值为 `null` 时 `= / <> / > / >= / < / <= / BETWEEN / NOT BETWEEN / IN / NOT IN` 全是 `UNKNOWN`，
///   既不命中也不否命中——`score` 为 `null` 的 id=4 在 `eq` 与 `ne` 两侧都不出现；
/// - `NOT UNKNOWN` 仍是 `UNKNOWN`，所以 id=4 在 `not(score > 5)` 里不出现；
/// - `UNKNOWN AND FALSE = FALSE`、`UNKNOWN OR TRUE = TRUE`（Kleene 逻辑），`and` / `or` 两格靠它。
///
/// **空集合是常量，不是 `UNKNOWN`**（这是已声明语义，不是本 oracle 的推测）：
/// `Membership#in(Collection)` 的 javadoc 写「空集合匹配不到任何行（恒假）」、
/// `#notIn(Collection)` 写「空集合匹配所有行（恒真）」；实现见 `core/builder/Specs.java` 的
/// `membership(...)`——`values.isEmpty()` 时直接返回 `Constant.of(negated)`。常量不引用列，
/// 因此 `notIn(空集合)` **命中包括 `score` 为 `null` 的 id=4 在内的全部 6 行**，`in(空集合)` 一行都不命中。
/// 这两格就是把「空集合折成 1=0 / 1=1」这个决定钉住。
///
/// **短路算子表示「没有这条条件」**（`docs/USAGE.md` §3.1）：`eqIfNotNull(null)` /
/// `eqIfNotEmpty("")` / `containsIfNotNull(null)` / `inIfNotNull(null)` 都不产生谓词，
/// 等价于不写这一条，因此命中全部 6 行——不是「匹配 null 行」，也不是「恒假」。
///
/// **`startsWith` / `endsWith` 用 `like` 表达**：3.x 不提供这两个算子（`docs/USAGE.md` §14
/// 「不提供的能力」把替代写成 `like("v%")` / `like("%v")`），所以这两格的后端一侧是
/// `like("app%")` / `like("%berry")`，内存一侧是前缀 / 后缀匹配。
///
/// @param <T> 后端实体类型
public abstract class PredicateDifferentialTest<T extends Item> extends ConsistencyTestBase<T> {

    @Override
    protected List<FixtureRow> fixtureRows() {
        return Fixtures.OPERATOR_ROWS;
    }

    /// 27 格各一条用例：真库谓词结果与内存三值逻辑结果逐元素比对。
    ///
    /// 用 `@TestFactory` 而不是 `@ParameterizedTest`：`junit-jupiter-params` 不在
    /// `nextentity-testing` 的编译类路径上（本模块只声明 `junit-jupiter-api`），改 pom 是红线；
    /// 动态测试同样做到「一份用例体、多格参数化执行」，报表里每格仍是独立用例。
    ///
    /// @return 每格谓词一条动态用例
    @TestFactory
    Stream<DynamicTest> everyPredicateMatchesMemoryOracle() {
        return Matrix.of(this).stream().map(cell -> DynamicTest.dynamicTest(cell.name(), () -> assertCell(cell)));
    }

    private void assertCell(Case<T> cell) {
        List<Long> expected = Fixtures.OPERATOR_ROWS.stream()
                .filter(cell.memory())
                .map(FixtureRow::id)
                .toList();
        cell.shape().verify(cell.name(), expected, Fixtures.OPERATOR_ROWS.size());

        List<Long> actual = cell.backend().apply(query()).orderBy(idRef()).asc().list().stream()
                .map(Item::id)
                .toList();
        assertThat(actual).as("%s：真库结果必须与内存三值逻辑逐元素一致", cell.name()).containsExactlyElementsOf(expected);
    }

    /// 一格用例：名称（同时是动态用例的显示名）+ 两条互不相交的期望值来源。
    ///
    /// @param name    用例名
    /// @param backend 后端一侧：把条件挂到全新查询上的动作
    /// @param memory  内存一侧：只读夹具原始行、自己实现三值逻辑的判定
    /// @param shape   期望形状，用于 oracle 自检
    /// @param <E>     后端实体类型
    private record Case<E extends Item>(
            String name, BackendSide<E> backend, Predicate<FixtureRow> memory, Shape shape) {}

    /// 后端一侧的入口：拿一条全新查询，挂上条件，交回可继续排序 / 终止的步骤。
    ///
    /// 用「动作」而不是直接存一个 `Predicate`：根级字符串算子（`contains` / `like` /
    /// `eqIfNotEmpty`）只能从 `where(PathRef.StringRef)` 的链式形态拿到，`Path.of(nameRef())`
    /// 上没有 `StringOps`；两种形态都能收敛到 `BaseWhereStep`，所以这里统一成动作。
    ///
    /// @param <E> 后端实体类型
    @FunctionalInterface
    private interface BackendSide<E extends Item> {

        /// 挂条件。
        ///
        /// @param fresh 全新查询入口，条件不得跨用例累积
        /// @return 可继续排序与取行的步骤
        BaseWhereStep<E, E> apply(EntityQuery<E> fresh);
    }

    /// 期望形状：oracle 自检，挡住「期望值算成空集 / 全表」这种退化。
    ///
    /// 这不是对生产代码的断言，而是对**期望值本身**的断言：本该命中一部分行的格，如果内存一侧
    /// 算出空集或全表，这格的差分就没有区分力（写错的后端照样通过），所以要在这里先红。
    /// 确实就该是空集 / 全表的格（`in(空)`、`notIn(空)`、短路算子）显式标出来。
    private enum Shape {
        PARTIAL,
        EMPTY,
        ALL;

        void verify(String name, List<Long> expected, int total) {
            switch (this) {
                case PARTIAL ->
                    assertThat(expected).as("%s 的期望值必须是真子集", name).isNotEmpty().hasSizeLessThan(total);
                case EMPTY -> assertThat(expected).as("%s 的期望值必须为空", name).isEmpty();
                case ALL -> assertThat(expected).as("%s 的期望值必须是全表", name).hasSize(total);
            }
        }
    }

    /// 用例表：一格 = 名称 + 后端一侧 + 内存一侧 + 期望形状。
    ///
    /// 单独成一个类，是为了让主类的耦合面只包含「表结构 + 执行」，用例本体集中在这里；
    /// 内存一侧的 `Truth` / `Atom` / `Numbers` / `Texts` 也放在这里，物理上就与后端路径分开。
    private static final class Matrix {

        /// 全表 27 格。
        ///
        /// @param base 后端接线（只用来取属性引用，不读任何结果）
        /// @param <E>  后端实体类型
        /// @return 按「比较 → 区间 → 集合 → 空值 → 文本 → 枚举 → 逻辑 → 条件化」排列的用例表
        static <E extends Item> List<Case<E>> of(ConsistencyTestBase<E> base) {
            List<Case<E>> cells = new ArrayList<>();

            // ---- 比较：id=4（score=null）在 eq 与 ne 两侧都不出现 ----
            cells.add(new Case<>(
                    "eq(score,10)",
                    query -> query.where(Path.of(base.scoreRef()).eq(10)),
                    Numbers.eq(FixtureRow::score, 10).keepsOnlyTrue(),
                    Shape.PARTIAL));
            cells.add(new Case<>(
                    "ne(score,10)",
                    query -> query.where(Path.of(base.scoreRef()).ne(10)),
                    Numbers.ne(FixtureRow::score, 10).keepsOnlyTrue(),
                    Shape.PARTIAL));
            cells.add(new Case<>(
                    "gt(score,5)",
                    query -> query.where(Path.of(base.scoreRef()).gt(5)),
                    Numbers.gt(FixtureRow::score, 5).keepsOnlyTrue(),
                    Shape.PARTIAL));
            cells.add(new Case<>(
                    "ge(score,5)",
                    query -> query.where(Path.of(base.scoreRef()).ge(5)),
                    Numbers.ge(FixtureRow::score, 5).keepsOnlyTrue(),
                    Shape.PARTIAL));
            cells.add(new Case<>(
                    "lt(score,5)",
                    query -> query.where(Path.of(base.scoreRef()).lt(5)),
                    Numbers.lt(FixtureRow::score, 5).keepsOnlyTrue(),
                    Shape.PARTIAL));
            cells.add(new Case<>(
                    "le(score,5)",
                    query -> query.where(Path.of(base.scoreRef()).le(5)),
                    Numbers.le(FixtureRow::score, 5).keepsOnlyTrue(),
                    Shape.PARTIAL));

            // ---- 区间：闭区间；NOT BETWEEN 是 NOT(BETWEEN)，id=4 两侧都不进 ----
            cells.add(new Case<>(
                    "between(score,5,20)",
                    query -> query.where(Path.of(base.scoreRef()).between(5, 20)),
                    Numbers.between(FixtureRow::score, 5, 20).keepsOnlyTrue(),
                    Shape.PARTIAL));
            cells.add(new Case<>(
                    "notBetween(score,5,20)",
                    query -> query.where(Path.of(base.scoreRef()).notBetween(5, 20)),
                    Numbers.between(FixtureRow::score, 5, 20).negate().keepsOnlyTrue(),
                    Shape.PARTIAL));

            // ---- 集合：非空集合对 NULL 行是 UNKNOWN；空集合折成常量，NULL 行也跟着命中/不命中 ----
            cells.add(new Case<>(
                    "in(score,[10,30])",
                    query -> query.where(Path.of(base.scoreRef()).in(List.of(10, 30))),
                    Numbers.in(FixtureRow::score, List.of(10, 30)).keepsOnlyTrue(),
                    Shape.PARTIAL));
            cells.add(new Case<>(
                    "in(score,[])",
                    query -> query.where(Path.of(base.scoreRef()).in(List.<Integer>of())),
                    Numbers.in(FixtureRow::score, List.of()).keepsOnlyTrue(),
                    Shape.EMPTY));
            cells.add(new Case<>(
                    "notIn(score,[10,30])",
                    query -> query.where(Path.of(base.scoreRef()).notIn(List.of(10, 30))),
                    Numbers.notIn(FixtureRow::score, List.of(10, 30)).keepsOnlyTrue(),
                    Shape.PARTIAL));
            cells.add(new Case<>(
                    "notIn(score,[])",
                    query -> query.where(Path.of(base.scoreRef()).notIn(List.<Integer>of())),
                    Numbers.notIn(FixtureRow::score, List.of()).keepsOnlyTrue(),
                    Shape.ALL));

            // ---- 空值 ----
            cells.add(new Case<>(
                    "isNull(note)",
                    query -> query.where(Path.of(base.noteRef()).isNull()),
                    Atom.isNull(FixtureRow::note).keepsOnlyTrue(),
                    Shape.PARTIAL));
            cells.add(new Case<>(
                    "isNotNull(note)",
                    query -> query.where(Path.of(base.noteRef()).isNotNull()),
                    Atom.isNotNull(FixtureRow::note).keepsOnlyTrue(),
                    Shape.PARTIAL));

            // ---- 文本：contains 自带 %...%；startsWith / endsWith 用 like 表达 ----
            cells.add(new Case<>(
                    "contains(name,app)",
                    query -> query.where(base.nameRef()).contains("app"),
                    Texts.contains(FixtureRow::name, "app").keepsOnlyTrue(),
                    Shape.PARTIAL));
            cells.add(new Case<>(
                    "like(name,app%)",
                    query -> query.where(base.nameRef()).like("app%"),
                    Texts.prefix(FixtureRow::name, "app").keepsOnlyTrue(),
                    Shape.PARTIAL));
            cells.add(new Case<>(
                    "like(name,%berry)",
                    query -> query.where(base.nameRef()).like("%berry"),
                    Texts.suffix(FixtureRow::name, "berry").keepsOnlyTrue(),
                    Shape.PARTIAL));

            // ---- 枚举 ----
            cells.add(new Case<>(
                    "eq(status,ACTIVE)",
                    query -> query.where(Path.of(base.statusRef()).eq(ItemStatus.ACTIVE)),
                    Atom.of(row -> row.status() == ItemStatus.ACTIVE).keepsOnlyTrue(),
                    Shape.PARTIAL));

            // ---- 逻辑：Kleene 三值逻辑 ----
            cells.add(new Case<>(
                    "and(score>5,note is not null)",
                    query -> query.where(Path.of(base.scoreRef())
                            .gt(5)
                            .and(Path.of(base.noteRef()).isNotNull())),
                    Numbers.gt(FixtureRow::score, 5)
                            .and(Atom.isNotNull(FixtureRow::note))
                            .keepsOnlyTrue(),
                    Shape.PARTIAL));
            cells.add(new Case<>(
                    "or(score<0,name=apple pie)",
                    query -> query.where(Path.of(base.scoreRef())
                            .lt(0)
                            .or(Path.of(base.nameRef()).eq(APPLE_PIE_NAME))),
                    Numbers.lt(FixtureRow::score, 0)
                            .or(Texts.eq(FixtureRow::name, APPLE_PIE_NAME))
                            .keepsOnlyTrue(),
                    Shape.PARTIAL));
            cells.add(new Case<>(
                    "not(score>5)",
                    query -> query.where(Path.of(base.scoreRef()).gt(5).not()),
                    Numbers.gt(FixtureRow::score, 5).negate().keepsOnlyTrue(),
                    Shape.PARTIAL));

            // ---- 条件化算子：短路 = 不产生条件 = 全部 6 行 ----
            cells.add(new Case<>(
                    "eqIfNotNull(score,null)",
                    query -> query.where(Path.of(base.scoreRef()).eqIfNotNull(null)),
                    row -> true,
                    Shape.ALL));
            cells.add(new Case<>(
                    "eqIfNotEmpty(name,empty)",
                    query -> query.where(base.nameRef()).eqIfNotEmpty(""),
                    row -> true,
                    Shape.ALL));
            cells.add(new Case<>(
                    "containsIfNotNull(name,null)",
                    query -> query.where(base.nameRef()).containsIfNotNull(null),
                    row -> true,
                    Shape.ALL));
            cells.add(new Case<>(
                    "inIfNotNull(score,null)",
                    query -> query.where(Path.of(base.scoreRef()).inIfNotNull(null)),
                    row -> true,
                    Shape.ALL));

            // ---- 条件化算子：值非空时等价于对应的非短路算子 ----
            cells.add(new Case<>(
                    "inIfNotNull(score,[10,30])",
                    query -> query.where(Path.of(base.scoreRef()).inIfNotNull(List.of(10, 30))),
                    Numbers.inIfNotNull(FixtureRow::score, List.of(10, 30)).keepsOnlyTrue(),
                    Shape.PARTIAL));
            cells.add(new Case<>(
                    "containsIfNotNull(name,app)",
                    query -> query.where(base.nameRef()).containsIfNotNull("app"),
                    Texts.containsIfNotNull(FixtureRow::name, "app").keepsOnlyTrue(),
                    Shape.PARTIAL));

            return cells;
        }

        /// SQL 三值逻辑。
        private enum Truth {
            TRUE,
            FALSE,
            UNKNOWN;

            /// 由布尔判定得到确定的真值（不产生 `UNKNOWN`）。
            ///
            /// @param holds 判定结果
            /// @return `TRUE` 或 `FALSE`
            static Truth of(boolean holds) {
                return holds ? TRUE : FALSE;
            }

            /// Kleene 逻辑与：`FALSE` 吸收一切，`UNKNOWN AND TRUE = UNKNOWN`。
            ///
            /// @param other 另一个真值
            /// @return 组合后的真值
            Truth and(Truth other) {
                if (this == FALSE || other == FALSE) {
                    return FALSE;
                }
                return this == TRUE && other == TRUE ? TRUE : UNKNOWN;
            }

            /// Kleene 逻辑或：`TRUE` 吸收一切，`UNKNOWN OR FALSE = UNKNOWN`。
            ///
            /// @param other 另一个真值
            /// @return 组合后的真值
            Truth or(Truth other) {
                if (this == TRUE || other == TRUE) {
                    return TRUE;
                }
                return this == FALSE && other == FALSE ? FALSE : UNKNOWN;
            }

            /// 逻辑非：`NOT UNKNOWN = UNKNOWN`（SQL 在这里与二值布尔不同，正是 NULL 行既不在
            /// `between` 也不在 `notBetween` 里的原因）。
            ///
            /// @return 取反后的真值
            Truth negate() {
                return switch (this) {
                    case TRUE -> FALSE;
                    case FALSE -> TRUE;
                    case UNKNOWN -> UNKNOWN;
                };
            }
        }

        /// 内存侧的一个原子条件：只读夹具原始行，返回三值真值。
        ///
        /// **不许**调用 nextentity 的生产代码，也不许读后端结果——独立性就是这个 oracle 的证据来源。
        @FunctionalInterface
        private interface Atom {

            /// 对一行求值。
            ///
            /// @param row 夹具原始行
            /// @return `TRUE` / `FALSE` / `UNKNOWN`
            Truth eval(FixtureRow row);

            /// 由布尔判定构造原子条件（用于不涉及 `null` 的判定，如枚举相等）。
            ///
            /// @param holds 判定
            /// @return 原子条件
            static Atom of(Predicate<FixtureRow> holds) {
                return row -> Truth.of(holds.test(row));
            }

            /// 列值为 `null`。
            ///
            /// @param column 列取值函数
            /// @return 原子条件
            static Atom isNull(Function<FixtureRow, ?> column) {
                return row -> Truth.of(column.apply(row) == null);
            }

            /// 列值不为 `null`。
            ///
            /// @param column 列取值函数
            /// @return 原子条件
            static Atom isNotNull(Function<FixtureRow, ?> column) {
                return row -> Truth.of(column.apply(row) != null);
            }

            /// Kleene 逻辑与。
            ///
            /// @param other 另一个原子条件
            /// @return 组合后的原子条件
            default Atom and(Atom other) {
                return row -> eval(row).and(other.eval(row));
            }

            /// Kleene 逻辑或。
            ///
            /// @param other 另一个原子条件
            /// @return 组合后的原子条件
            default Atom or(Atom other) {
                return row -> eval(row).or(other.eval(row));
            }

            /// 逻辑非：`UNKNOWN` 取反仍是 `UNKNOWN`。
            ///
            /// @return 取反后的原子条件
            default Atom negate() {
                return row -> eval(row).negate();
            }

            /// 三值转二值：只有 `TRUE` 命中，`UNKNOWN` 与 `FALSE` 都不命中（`WHERE` 的语义）。
            ///
            /// @return 内存一侧的最终判定
            default Predicate<FixtureRow> keepsOnlyTrue() {
                return row -> eval(row) == Truth.TRUE;
            }
        }

        /// 数值列上的原子条件：列值为 `null` 时一律 `UNKNOWN`。
        private static final class Numbers {

            /// 等于。
            ///
            /// @param column 列取值函数
            /// @param value  比较值
            /// @return 原子条件
            static Atom eq(Function<FixtureRow, Integer> column, int value) {
                return row -> {
                    Integer actual = column.apply(row);
                    return actual == null ? Truth.UNKNOWN : Truth.of(actual == value);
                };
            }

            /// 不等于。
            ///
            /// @param column 列取值函数
            /// @param value  比较值
            /// @return 原子条件
            static Atom ne(Function<FixtureRow, Integer> column, int value) {
                return row -> {
                    Integer actual = column.apply(row);
                    return actual == null ? Truth.UNKNOWN : Truth.of(actual != value);
                };
            }

            /// 大于。
            ///
            /// @param column 列取值函数
            /// @param value  比较值
            /// @return 原子条件
            static Atom gt(Function<FixtureRow, Integer> column, int value) {
                return row -> {
                    Integer actual = column.apply(row);
                    return actual == null ? Truth.UNKNOWN : Truth.of(actual > value);
                };
            }

            /// 大于等于。
            ///
            /// @param column 列取值函数
            /// @param value  比较值
            /// @return 原子条件
            static Atom ge(Function<FixtureRow, Integer> column, int value) {
                return row -> {
                    Integer actual = column.apply(row);
                    return actual == null ? Truth.UNKNOWN : Truth.of(actual >= value);
                };
            }

            /// 小于。
            ///
            /// @param column 列取值函数
            /// @param value  比较值
            /// @return 原子条件
            static Atom lt(Function<FixtureRow, Integer> column, int value) {
                return row -> {
                    Integer actual = column.apply(row);
                    return actual == null ? Truth.UNKNOWN : Truth.of(actual < value);
                };
            }

            /// 小于等于。
            ///
            /// @param column 列取值函数
            /// @param value  比较值
            /// @return 原子条件
            static Atom le(Function<FixtureRow, Integer> column, int value) {
                return row -> {
                    Integer actual = column.apply(row);
                    return actual == null ? Truth.UNKNOWN : Truth.of(actual <= value);
                };
            }

            /// 闭区间，两端都含。
            ///
            /// @param column 列取值函数
            /// @param from   下界，含
            /// @param to     上界，含
            /// @return 原子条件
            static Atom between(Function<FixtureRow, Integer> column, int from, int to) {
                return row -> {
                    Integer actual = column.apply(row);
                    return actual == null ? Truth.UNKNOWN : Truth.of(actual >= from && actual <= to);
                };
            }

            /// 在集合内；**空集合是常量恒假**，不引用列，因此 `null` 行也不命中。
            ///
            /// @param column 列取值函数
            /// @param values 候选集合
            /// @return 原子条件
            static Atom in(Function<FixtureRow, Integer> column, List<Integer> values) {
                if (values.isEmpty()) {
                    return row -> Truth.FALSE;
                }
                return row -> {
                    Integer actual = column.apply(row);
                    return actual == null ? Truth.UNKNOWN : Truth.of(values.contains(actual));
                };
            }

            /// 不在集合内；**空集合是常量恒真**，不引用列，因此 `null` 行也命中。
            ///
            /// 依据 `core/builder/Specs.java` 的 `membership(...)`：`values.isEmpty()` 时返回
            /// `Constant.of(negated)`，`negated=true` 即 `1=1`。
            ///
            /// @param column 列取值函数
            /// @param values 排除集合
            /// @return 原子条件
            static Atom notIn(Function<FixtureRow, Integer> column, List<Integer> values) {
                if (values.isEmpty()) {
                    return row -> Truth.TRUE;
                }
                return row -> {
                    Integer actual = column.apply(row);
                    return actual == null ? Truth.UNKNOWN : Truth.of(!values.contains(actual));
                };
            }

            /// 等于，值为 `null` 时短路成「没有条件」。
            ///
            /// @param column 列取值函数
            /// @param value  比较值，可为 `null`
            /// @return 原子条件
            static Atom eqIfNotNull(Function<FixtureRow, Integer> column, Integer value) {
                if (value == null) {
                    return row -> Truth.TRUE;
                }
                return eq(column, value);
            }

            /// 在集合内，集合为 `null` 时短路成「没有条件」。
            ///
            /// @param column 列取值函数
            /// @param values 候选集合，可为 `null`
            /// @return 原子条件
            static Atom inIfNotNull(Function<FixtureRow, Integer> column, List<Integer> values) {
                if (values == null) {
                    return row -> Truth.TRUE;
                }
                return in(column, values);
            }
        }

        /// 文本列上的原子条件：列值为 `null` 时一律 `UNKNOWN`。
        private static final class Texts {

            /// 相等。
            ///
            /// @param column 列取值函数
            /// @param value  比较值
            /// @return 原子条件
            static Atom eq(Function<FixtureRow, String> column, String value) {
                return row -> {
                    String actual = column.apply(row);
                    return actual == null ? Truth.UNKNOWN : Truth.of(actual.equals(value));
                };
            }

            /// 包含子串：对应后端 `contains(value)`（渲染成 `%value%`）。
            ///
            /// @param column 列取值函数
            /// @param value  子串
            /// @return 原子条件
            static Atom contains(Function<FixtureRow, String> column, String value) {
                return row -> {
                    String actual = column.apply(row);
                    return actual == null ? Truth.UNKNOWN : Truth.of(actual.contains(value));
                };
            }

            /// 前缀匹配：对应后端 `like("value%")`。
            ///
            /// @param column 列取值函数
            /// @param value  前缀
            /// @return 原子条件
            static Atom prefix(Function<FixtureRow, String> column, String value) {
                return row -> {
                    String actual = column.apply(row);
                    return actual == null ? Truth.UNKNOWN : Truth.of(actual.startsWith(value));
                };
            }

            /// 后缀匹配：对应后端 `like("%value")`。
            ///
            /// @param column 列取值函数
            /// @param value  后缀
            /// @return 原子条件
            static Atom suffix(Function<FixtureRow, String> column, String value) {
                return row -> {
                    String actual = column.apply(row);
                    return actual == null ? Truth.UNKNOWN : Truth.of(actual.endsWith(value));
                };
            }

            /// 相等，值为 `null` 或空串时短路成「没有条件」。
            ///
            /// @param column 列取值函数
            /// @param value  比较值，可为 `null`
            /// @return 原子条件
            static Atom eqIfNotEmpty(Function<FixtureRow, String> column, String value) {
                if (value == null || value.isEmpty()) {
                    return row -> Truth.TRUE;
                }
                return eq(column, value);
            }

            /// 包含子串，值为 `null` 时短路成「没有条件」。
            ///
            /// @param column 列取值函数
            /// @param value  子串，可为 `null`
            /// @return 原子条件
            static Atom containsIfNotNull(Function<FixtureRow, String> column, String value) {
                if (value == null) {
                    return row -> Truth.TRUE;
                }
                return contains(column, value);
            }
        }
    }
}
