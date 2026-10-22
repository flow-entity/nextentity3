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
/// id=4 bandage owner=null
/// ```
///
/// @param <T> 后端实体类型
public abstract class AssociationConsistencyTest<T extends Asset> {

    /// 固定夹具 id。
    protected static final long ANVIL_ID = 1L;
    /// 固定夹具 id。
    protected static final long AXE_ID = 2L;
    /// 固定夹具 id。
    protected static final long BOLT_ID = 3L;
    /// 固定夹具 id。
    protected static final long BANDAGE_ID = 4L;

    /// 固定夹具 owner 名称。
    protected static final String ALPHA = "alpha";
    /// 固定夹具 owner 名称。
    protected static final String BETA = "beta";
    /// 固定夹具里不存在的 owner 名称。
    protected static final String MISSING_OWNER = "gamma";

    /// 每个用例前重建固定夹具。
    @BeforeEach
    final void seed() {
        seedData(List.of(
                new AssetFixture(ANVIL_ID, "anvil", ALPHA),
                new AssetFixture(AXE_ID, "axe", ALPHA),
                new AssetFixture(BOLT_ID, "bolt", BETA),
                new AssetFixture(BANDAGE_ID, "bandage", null)));
    }

    /// 全新的查询入口。
    ///
    /// @return 查询入口
    protected abstract EntityQuery<T> query();

    /// 清空并写入夹具（含 owner 的落库）；返回后数据必须对新查询可见。
    ///
    /// @param fixtures 夹具描述
    protected abstract void seedData(List<AssetFixture> fixtures);

    /// 两段嵌套路径：`EntityPath.of(T::owner).get(Owner::name)`。
    ///
    /// @return 嵌套字符串路径
    protected abstract StringPath<T> ownerNamePath();

    /// 取结果集的主键序列。
    ///
    /// @param rows 查询结果
    /// @return 主键列表
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
    @Test
    void nestedSliceCountsRowsNotJoinPairs() {
        Slice<T> slice = query().where(ownerNamePath())
                .in(List.of(ALPHA, BETA))
                .orderBy(ownerNamePath())
                .asc()
                .slice(0, 2);

        assertThat(slice.total()).isEqualTo(3L);
        assertThat(slice.data()).hasSize(2);
    }

    /// 嵌套路径 `orderBy`：按 join 出来的列排序（`null` 关联行的位置由数据库决定，故只断言非空部分）。
    @Test
    void nestedOrderBySortsByJoinedColumn() {
        List<String> ownerNames =
                query().select(ownerNamePath()).orderBy(ownerNamePath()).asc().list();

        assertThat(ownerNames.stream().filter(Objects::nonNull).toList()).containsExactly(ALPHA, ALPHA, BETA);
    }
}
