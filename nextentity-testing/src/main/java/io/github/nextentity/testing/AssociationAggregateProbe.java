package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.api.model.Tuple2;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/// 关联嵌套路径上的**聚合与分组**：真库一侧发聚合查询，内存一侧只对夹具原始值数数 / 取极值。
///
/// 语义要点全部来自 SQL 的三值逻辑，而不是后端实现：`COUNT(列)` / `COUNT(DISTINCT 列)` /
/// `MIN` / `MAX` 都**跳过 `null`**，所以「`null` 关联行」在聚合里的表现是「不参与」，
/// 在分组里则是「自成一组但计数为 0」。这三件事各有一格，任何一格把 `null` 当成普通值都会红。
///
/// **本类没有「按 join 列分组」的格子**，原因是 api 的形状：`GroupByStep#groupBy` 只接
/// `PathRef`（用户写的方法引用），而嵌套路径是 `Path`（值对象，见 `Path` 的类注释「`Path` 不继承
/// `PathRef`」），两者刻意不互通。因此「按 join 列分组」在当前 api 上不可写，本类改为
/// 「按主键分组 + 聚合嵌套列」，这是同一份 join 结果集上的等价差分；真要做「按 join 列分组」，
/// 得先给 `groupBy` 加 `Path` 重载（那是 api 变更，不在本测试任务范围）。
///
/// @param <T> 后端实体类型
final class AssociationAggregateProbe<T extends Asset> {

    /// 按主键分组数 join 列：`null` 关联那一组的计数必须是 `0`（`COUNT(列)` 跳过 `null`）。
    private static final String GROUPED_COUNT = "groupBy(id) count(owner.name)";

    /// 同上但先按嵌套条件过滤：`null` 关联那一组整组消失（`WHERE` 不保留 `UNKNOWN` 行）。
    private static final String GROUPED_COUNT_FILTERED = "groupBy(id) count(owner.name) where isNotNull";

    /// 全表去重计数：`null` 不计入，所以是 2（alpha、beta）而不是 3。
    private static final String COUNT_DISTINCT = "countDistinct(owner.name)";

    /// 全表最大 / 最小值：`null` 不参与比较。
    private static final String MAX_OWNER = "max(owner.name)";

    private static final String MIN_OWNER = "min(owner.name)";

    /// 取值域只剩 `null` 关联行时：计数 `0`、极值 `null`（不是 `0` / 空串）。
    private static final String NULL_SCOPE_COUNT = "count(owner.name) 在只有 null 关联行的域上";

    private static final String NULL_SCOPE_MIN = "min(owner.name) 在只有 null 关联行的域上";

    private final List<AssetFixture> rows;

    private final Supplier<EntityQuery<T>> query;

    private final StringPath<T> ownerNamePath;

    private final PathRef<T, Long> idRef;

    AssociationAggregateProbe(
            List<AssetFixture> rows,
            Supplier<EntityQuery<T>> query,
            StringPath<T> ownerNamePath,
            PathRef<T, Long> idRef) {
        this.rows = rows;
        this.query = query;
        this.ownerNamePath = ownerNamePath;
        this.idRef = idRef;
    }

    /// 七条聚合用例：分组计数两格、全表去重计数、极值两格、`null` 域两格。
    ///
    /// @return 每个聚合形态一条动态用例
    Stream<DynamicTest> aggregateTests() {
        List<Tuple2<Long, Long>> perId = groupedCounts(false);
        List<Tuple2<Long, Long>> perIdNonNull = groupedCounts(true);
        // oracle 自检（不是恒真断言）：夹具里必须真的有一个「计数为 0」的组，否则 `null` 关联的
        // 聚合语义这一族根本没被覆盖。
        assertThat(perId.stream().map(Tuple2::get1))
                .as("夹具必须含一个 null 关联组（计数 0）")
                .contains(0L);
        return Stream.of(
                DynamicTest.dynamicTest(GROUPED_COUNT, () -> assertThat(query.get()
                                .select(idRef, ownerNamePath.count())
                                .groupBy(idRef)
                                .orderBy(idRef)
                                .asc()
                                .list())
                        .as("按主键分组的 `count(owner.name)`（null 关联组为 0）")
                        .containsExactlyElementsOf(perId)),
                DynamicTest.dynamicTest(GROUPED_COUNT_FILTERED, () -> assertThat(query.get()
                                .select(idRef, ownerNamePath.count())
                                .where(ownerNamePath.isNotNull())
                                .groupBy(idRef)
                                .orderBy(idRef)
                                .asc()
                                .list())
                        .as("嵌套条件之后按主键分组：null 关联组整组消失")
                        .containsExactlyElementsOf(perIdNonNull)),
                DynamicTest.dynamicTest(COUNT_DISTINCT, () -> assertThat(query.get()
                                .select(ownerNamePath.countDistinct())
                                .list())
                        .as("`countDistinct(owner.name)` 不计 null")
                        .containsExactly((Long) AssociationJoinOracle.aggregate(
                                rows,
                                AssociationJoinOracle.Measure.COUNT_DISTINCT,
                                AssociationJoinOracle.Scope.ALL_ROWS))),
                DynamicTest.dynamicTest(MAX_OWNER, () -> assertThat(
                                query.get().select(ownerNamePath.max()).list())
                        .as("`max(owner.name)` 跳过 null")
                        .containsExactly((String) AssociationJoinOracle.aggregate(
                                rows, AssociationJoinOracle.Measure.MAX, AssociationJoinOracle.Scope.ALL_ROWS))),
                DynamicTest.dynamicTest(MIN_OWNER, () -> assertThat(
                                query.get().select(ownerNamePath.min()).list())
                        .as("`min(owner.name)` 跳过 null")
                        .containsExactly((String) AssociationJoinOracle.aggregate(
                                rows, AssociationJoinOracle.Measure.MIN, AssociationJoinOracle.Scope.ALL_ROWS))),
                DynamicTest.dynamicTest(NULL_SCOPE_COUNT, () -> assertThat(query.get()
                                .select(ownerNamePath.count())
                                .where(ownerNamePath.isNull())
                                .list())
                        .as("只有 null 关联行时 `count(owner.name)` 是 0，不是 1")
                        .containsExactly((Long) AssociationJoinOracle.aggregate(
                                rows, AssociationJoinOracle.Measure.COUNT, AssociationJoinOracle.Scope.NULL_JOIN_ROW))),
                DynamicTest.dynamicTest(NULL_SCOPE_MIN, () -> assertThat(query.get()
                                .select(ownerNamePath.min())
                                .where(ownerNamePath.isNull())
                                .list())
                        .as("只有 null 关联行时 `min(owner.name)` 是 null，不是空串")
                        .containsExactly((String) AssociationJoinOracle.aggregate(
                                rows, AssociationJoinOracle.Measure.MIN, AssociationJoinOracle.Scope.NULL_JOIN_ROW))));
    }

    /// 内存侧的分组计数：主键 → 该组里非 `null` 的 join 值个数，按主键升序。
    ///
    /// @param nonNullOnly 是否丢掉计数为 `0` 的组（模拟「嵌套条件把 `UNKNOWN` 行滤掉」）
    /// @return 有序的 `(id, count)` 序列
    private List<Tuple2<Long, Long>> groupedCounts(boolean nonNullOnly) {
        List<Tuple2<Long, Long>> counts = new ArrayList<>();
        AssociationJoinOracle.countPerId(rows).forEach((id, count) -> {
            if (!nonNullOnly || count > 0) {
                counts.add(new Tuple2<Long, Long>(id, count));
            }
        });
        return counts;
    }
}
