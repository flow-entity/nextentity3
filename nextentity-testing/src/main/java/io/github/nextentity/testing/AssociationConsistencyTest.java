package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.api.model.Slice;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// 关联嵌套路径（`EntityPath.of(A::getB).get(B::getC)`）的一致性：这是 D1 里唯一失败的那一族。
///
/// 失败形态（2.2.3 迁移时实测）：
/// `无法从方法引用解析属性路径: PathValues$Nested` / `NoSuchMethodException: writeReplace()`——
/// 嵌套路径在运行期解析不出属性名，于是 `where(...)` 根本拿不到可用的路径。
///
/// 语义约定：
/// - 嵌套路径的条件要**保留**关联为空的行参与 LEFT JOIN 语义：`isNull` 命中它们，`eq`/`in` 不命中；
/// - 分页的 `total` 是满足条件的**实体行数**，不因 join 而放大。
///
/// 固定夹具：
/// ```text
/// id=1 anvil   owner=alpha
/// id=2 axe     owner=alpha
/// id=3 bolt    owner=beta
/// id=4 bandage owner=null      ← NULL 关联锚点
/// id=5 chisel  owner=aardvark  ← alpha 的**下界**邻居
/// id=6 clamp   owner=amber     ← alpha 与 beta **之间**的取值
/// id=7 chain   owner=alps      ← 共享 `al` 前缀但不被 `_lpha` 命中
/// ```
///
/// 后三行不是为了"数据更多"，而是为了**让算子两两可区分**：只有 alpha/beta 两个非空取值时，
/// `gt(alpha)` 与 `ne(alpha)` 都是 bolt 一行、`le(alpha)` 与 `eq(alpha)` 都是 anvil+axe 两行、
/// `between(alpha,beta)` 等于 `isNotNull`——把 `gt` 渲染成 `<>`、把 `between` 渲染成
/// `is not null`，17 个谓词格照样全绿。可区分性由
/// {@link AssociationDifferentialTest#predicateCellsAreMutuallyDistinguishable()} 逐对钉住。
///
/// @param <T> 后端实体类型
public abstract class AssociationConsistencyTest<T extends Asset> {

    protected static final long ANVIL_ID = 1L;

    protected static final long AXE_ID = 2L;

    protected static final long BOLT_ID = 3L;

    protected static final long BANDAGE_ID = 4L;

    protected static final long CHISEL_ID = 5L;

    protected static final long CLAMP_ID = 6L;

    protected static final long CHAIN_ID = 7L;

    protected static final String ALPHA = "alpha";

    protected static final String BETA = "beta";

    /// `alpha` 的**下界**邻居（`aardvark` < `alpha`）：让 `le(alpha)` 不同于 `eq(alpha)`、
    /// `ne(alpha)` 不同于 `gt(alpha)`。
    protected static final String AARDVARK = "aardvark";

    /// `alpha` 与 `beta` **之间**的取值：让 `ge(beta)` 不同于 `gt(alpha)`、`lt(beta)` 不同于 `le(alpha)`。
    protected static final String AMBER = "amber";

    /// 共享 `al` 前缀、长度 4：让 `like("al%")` 不同于 `like("_lpha")`。
    protected static final String ALPS = "alps";

    /// 每个用例前重建固定夹具。
    @BeforeEach
    final void seed() {
        seedData(List.of(
                new AssetFixture(ANVIL_ID, "anvil", ALPHA),
                new AssetFixture(AXE_ID, "axe", ALPHA),
                new AssetFixture(BOLT_ID, "bolt", BETA),
                new AssetFixture(BANDAGE_ID, "bandage", null),
                new AssetFixture(CHISEL_ID, "chisel", AARDVARK),
                new AssetFixture(CLAMP_ID, "clamp", AMBER),
                new AssetFixture(CHAIN_ID, "chain", ALPS)));
    }

    /// 全新的查询入口。
    ///
    /// @return 全新的查询入口：条件不得跨用例累积
    protected abstract EntityQuery<T> query();

    /// 清空并写入夹具（含 owner 的落库）；返回后数据必须对新查询可见。
    ///
    /// @param fixtures 夹具描述；`ownerName` 为 `null` 表示该行没有关联
    protected abstract void seedData(List<AssetFixture> fixtures);

    /// 两段嵌套路径：`EntityPath.of(T::owner).get(Owner::name)`。
    ///
    /// @return 两段嵌套的字符串路径（`owner` → `name`）
    protected abstract StringPath<T> ownerNamePath();

    /// 取结果集的主键序列。
    ///
    /// @param rows 查询结果
    /// @return 主键列表，保持查询返回的顺序
    protected final List<Long> ids(List<T> rows) {
        return rows.stream().map(Asset::id).toList();
    }

    /// 嵌套路径 `eq`：命中该 owner 的行。
    @Test
    void nestedEqMatchesRowsOfThatOwner() {
        List<T> rows = query().where(ownerNamePath()).eq(ALPHA).list();

        assertThat(ids(rows)).containsExactlyInAnyOrder(ANVIL_ID, AXE_ID);
    }

    /// 嵌套路径 `contains`：对 owner 名称做包含匹配。
    @Test
    void nestedContainsMatchesOwnerNameSubstring() {
        List<T> rows = query().where(ownerNamePath()).contains("bet").list();

        assertThat(ids(rows)).containsExactly(BOLT_ID);
    }

    /// 嵌套路径 `in`：多 owner 命中。
    @Test
    void nestedInMatchesAnyOwner() {
        List<T> rows = query().where(ownerNamePath()).in(List.of(ALPHA, BETA)).list();

        assertThat(ids(rows)).containsExactlyInAnyOrder(ANVIL_ID, AXE_ID, BOLT_ID);
    }

    /// 关联为空的行不参与 `eq`（LEFT JOIN 下它就是 `NULL = 'alpha'` → 不命中）。
    @Test
    void nestedEqExcludesRowsWithoutOwner() {
        List<T> rows = query().where(ownerNamePath()).eq(ALPHA).list();

        assertThat(ids(rows)).doesNotContain(BANDAGE_ID);
    }

    /// `isNull` 命中关联为空的行——这是 LEFT JOIN 语义的判据（内连接会把它丢掉）。
    @Test
    void nestedIsNullMatchesRowsWithoutOwner() {
        List<T> rows = query().where(ownerNamePath()).isNull().list();

        assertThat(ids(rows)).containsExactly(BANDAGE_ID);
    }

    /// 嵌套条件 + 分页：`total` 是实体行数，不因 join 放大；`data` 取一页。
    ///
    /// `data` 也钉主键集合，不只断 `hasSize(2)`：这一页是「按 owner 升序」的前两行，
    /// 而 alpha 只有 anvil/axe 两行、beta 只有 bolt 一行，所以前两行**必然是** anvil+axe
    /// （alpha 组内部相对顺序不定，故用 `containsExactlyInAnyOrder`）。若 join 把行放大、
    /// 或分页在放大后的结果集上切片，这一页会混进 bolt 或重复行，`hasSize(2)` 照样通过。
    @Test
    void nestedSliceCountsRowsNotJoinPairs() {
        Slice<T> slice = query().where(ownerNamePath())
                .in(List.of(ALPHA, BETA))
                .orderBy(ownerNamePath())
                .asc()
                .slice(0, 2);

        assertThat(slice.total()).isEqualTo(3L);
        assertThat(ids(slice.data())).containsExactlyInAnyOrder(ANVIL_ID, AXE_ID);
    }

    /// 嵌套路径 `orderBy`：按 join 出来的列排序（`null` 关联行的位置由数据库决定，故只断言非空部分）。
    ///
    /// 非空取值的升序是夹具的**手算常量**（`aardvark` < `alpha` < `alps` < `amber` < `beta`），
    /// 不是拿生产结果反推；完整序列（含 `null` 位置）另由
    /// {@link AssociationDifferentialTest#nestedOrderByPinsCompleteSequence()} 差分钉住。
    @Test
    void nestedOrderBySortsByJoinedColumn() {
        List<String> ownerNames =
                query().select(ownerNamePath()).orderBy(ownerNamePath()).asc().list();

        assertThat(ownerNames.stream().filter(Objects::nonNull).toList())
                .containsExactly(AARDVARK, ALPHA, ALPHA, ALPS, AMBER, BETA);
    }
}
