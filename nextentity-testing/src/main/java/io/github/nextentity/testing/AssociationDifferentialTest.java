package io.github.nextentity.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.PathRef;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/// 关联嵌套路径（`EntityPath.of(T::owner).get(Owner::name)`）的**差分**基类：每格都是
/// 「真库跑一遍」与「内存里按 LEFT JOIN + 三值逻辑独立算一遍」逐元素比对，两条路径不相交——
/// 内存侧不调生产代码、不读后端结果，只读 {@link #fixtureRows()} 的原始值。
///
/// 夹具七行（`AssetFixture` 复用 {@link AssociationConsistencyTest} 的常量）：
/// ```text
/// id=1 anvil   owner=alpha
/// id=2 axe     owner=alpha
/// id=3 bolt    owner=beta
/// id=4 bandage owner=null      ← NULL 关联锚点
/// id=5 chisel  owner=aardvark  ← alpha 的下界邻居
/// id=6 clamp   owner=amber     ← alpha 与 beta 之间的取值
/// id=7 chain   owner=alps      ← 共享 `al` 前缀但不被 `_lpha` 命中
/// ```
/// 后三行的作用是**让 17 个谓词格两两可区分**（二值域下 `gt(alpha)`≡`ne(alpha)`、
/// `le(alpha)`≡`eq(alpha)`、`between`≡`isNotNull`，把算子渲染错也全绿）；这一条由
/// {@link #predicateCellsAreMutuallyDistinguishable()} 逐对钉住。
/// 内存一侧把它当作 `asset LEFT JOIN owner` 的**结果集**求值：关联为 `null` 时 join 出来的
/// owner 列就是 `null`；`= / <> / > / < / between / in / notIn` 遇 `null` 一律 `UNKNOWN`（`WHERE`
/// 不保留），`isNull` / `isNotNull` 判的就是 `null` 本身。所以 `null` 关联行**只**被 `isNull()` 命中，
/// `eq(alpha)` 与 `ne(alpha)` 两侧都不命中——`ne` 里出现 bandage 就是「把 `NULL` 当普通值」或
/// 「嵌套路径渲染成内连接」的直接判据，{@link #nullJoinColumnMatchesNeitherEqNorNe()} 专钉这一条。
///
/// **排序口径（实测，不是照文档抄）**：H2 `MODE=MySQL` 与 MySQL 8.0 都把 `null` 当最小值，
/// 所以 `ORDER BY owner.name ASC` 里 bandage 排最前、`DESC` 里排最后；本类据此用
/// `containsExactlyElementsOf` 钉**完整序列（含 `null`）**，而不是只钉非空部分——
/// 只钉非空部分时「`null` 被排到中间」「降序时 `null` 没跟着反转」全部逃逸。
/// **PostgreSQL 相反**（`NULLS LAST` 是默认），换后端须按方言覆盖断言，不许把断言放宽成「非空部分」。
///
/// **聚合口径**：`count(路径)` / `countDistinct` / `min` / `max` 都跳过 `null`，因此 `null` 关联行
/// 对它们不计入；但它在 `groupBy` 里自成一组（`select(id, count(owner.name)).groupBy(id)` 上该组是 `0`）。
/// 「按 join 列分组」在当前 api 上**不可写**（`groupBy` 只接 `PathRef`，嵌套路径是 `Path`），
/// 理由与替代形态见 {@link AssociationAggregateProbe} 的类注释。
///
/// **分页口径**：`total` 是满足条件的**实体行数**，不因 join 放大；`data` 钉主键序列而不钉
/// `hasSize`——join 把行放大时「一页 2 行」照样成立，钉住 id 才看得出混进了谁。
///
/// **夹具单一真身**：本类**只**认 {@link #fixtureRows()} 一处定义。{@code seedData(...)} 被 `final`
/// 收口：先断言上游递下来的列表与 `fixtureRows()` 逐元素相同，再用 `fixtureRows()` 落库；
/// 内存侧（谓词、排序、聚合、分组、分页、投影）也全部读同一份。于是「灌 A 算 B」不可能发生——
/// 两侧要么是同一份数据，要么在 `@BeforeEach` 就红。要改夹具只改 `FIXTURE_ROWS` 一处。
///
/// 未覆盖：`having` 打在嵌套路径的聚合值上。`AggregateValue` 不接 `Nullness` / `Membership`，
/// `having` 也拿不到嵌套路径上的聚合主语，属于 api 侧明确不提供的能力，不在本类补。
///
/// `junit-jupiter-params` 不在本模块编译类路径上（只声明了 `junit-jupiter-api`），所以一格一条
/// `DynamicTest`，不用 `@ParameterizedTest`。
///
/// 三个「格子族」各自独立成类（{@link AssociationPredicateProbe} / {@link AssociationWindowProbe} /
/// {@link AssociationAggregateProbe}），本类只负责接线与夹具单一真身。拆开的原因是 PMD 的
/// `CouplingBetweenObjects`（阈值 20）：格子族塞在一个类里会同时抓住 `EntityQuery`、`Path`、
/// `Slice`、`Tuple2`、`Measure` 等十几条 api 与内部依赖，和「夹具」这件事没有关系。
///
/// @param <T> 后端实体类型
public abstract class AssociationDifferentialTest<T extends Asset> extends AssociationConsistencyTest<T> {

    /// 七行夹具：`ownerName` 为 `null` 的那行就是 join 不出 owner 的行（LEFT JOIN 的判据来源）。
    ///
    /// 唯一真身，只通过 {@link #fixtureRows()} 暴露。
    private static final List<AssetFixture> FIXTURE_ROWS = List.of(
            new AssetFixture(ANVIL_ID, "anvil", ALPHA),
            new AssetFixture(AXE_ID, "axe", ALPHA),
            new AssetFixture(BOLT_ID, "bolt", BETA),
            new AssetFixture(BANDAGE_ID, "bandage", null),
            new AssetFixture(CHISEL_ID, "chisel", AARDVARK),
            new AssetFixture(CLAMP_ID, "clamp", AMBER),
            new AssetFixture(CHAIN_ID, "chain", ALPS));

    /// 夹具的唯一真身：内存侧直接用它求值，落库也是它。
    ///
    /// `final` 是刻意的：一旦子类能覆写它，内存侧与落库侧就有了两处定义，错的夹具配对的期望值会静默变绿。
    ///
    /// @return 七行夹具（`asset LEFT JOIN owner` 的结果集形态）
    protected final List<AssetFixture> fixtureRows() {
        return FIXTURE_ROWS;
    }

    /// 落库入口的**唯一**实现：断言上游递下来的夹具与 {@link #fixtureRows()} 一致，再交给后端写入。
    ///
    /// 上游（`AssociationConsistencyTest#seed`）是 `final @BeforeEach`，它传什么由父类定；这里不接受
    /// 第二份定义——传进来的与 `fixtureRows()` 不同就直接红，而不是「灌 A 算 B」。
    ///
    /// @param fixtures 上游递下来的夹具描述
    @Override
    protected final void seedData(List<AssetFixture> fixtures) {
        assertThat(fixtures)
                .as("落库夹具必须与内存侧读的 fixtureRows() 是同一份数据，否则期望值与真实数据对不上")
                .containsExactlyElementsOf(fixtureRows());
        persistFixtures(fixtureRows());
    }

    /// 后端子类真正写库的地方：清空并写入给定夹具（含 owner 的落库），返回后数据必须对新查询可见。
    ///
    /// @param fixtures 要落库的夹具；`ownerName` 为 `null` 表示该行没有关联
    protected abstract void persistFixtures(List<AssetFixture> fixtures);

    /// 主键路径：分页与排序的 tiebreaker 都要它。
    ///
    /// @return 主键的属性路径
    protected abstract PathRef<T, Long> idRef();

    /// LEFT JOIN 语义的专条判据：`isNull` 恰好命中 bandage，`eq`/`ne`/`in`/`notIn` 都不含它，
    /// 且 `eq ∪ ne` 不是全表（`NULL = 'alpha'` 与 `NULL <> 'alpha'` 都是 `UNKNOWN`）。
    ///
    /// 若嵌套路径被渲染成内连接，bandage 会从 `isNull` 里消失；
    /// 若 `ne` 被渲染成 `NOT (owner.name = ?)`，bandage 会从 `ne` 里冒出来。两种写错都在这里当场红。
    @Test
    void nullJoinColumnMatchesNeitherEqNorNe() {
        predicates().assertLeftJoinSemantics();
    }

    /// 谓词格之间的**可区分性**自检：二值域夹具下 17 个格子只产出 6 种不同结果集
    /// （七行夹具下 15 种：只剩 `ne ≡ notIn`、`contains() ≡ isNotNull()` 两组语义本等价的
    /// 重复，其余 13 格两两不同），把 `gt` 渲染成 `<>`、把 `between` 渲染成 `is not null`、
    /// 把 `le` 渲染成 `=`、把 `contains` 渲染成 `>=` 都能全绿。
    ///
    /// 这条用例只检查"夹具够不够刁"，不检查生产代码：夹具一旦退化回 alpha/beta 两值，
    /// 这里先红，而不是让 17 条谓词格继续假装在验证算子。
    @Test
    void predicateCellsAreMutuallyDistinguishable() {
        predicates().assertCellsAreDistinguishable();
    }

    /// 完整谓词矩阵：17 格（`eq` / `ne` / `in` / `in([])` / `notIn` / `notIn([])` / `isNull` /
    /// `isNotNull` / `contains` / `contains("")` / `like` 两种通配 / `gt` / `ge` / `lt` / `le` / `between`），
    /// 每格一条真库执行 + 一次内存求值。
    ///
    /// @return 每个谓词格一条动态用例
    @TestFactory
    Stream<DynamicTest> everyNestedPredicateMatchesLeftJoinOracle() {
        return predicates().predicateTests();
    }

    /// 嵌套路径投影：`select(owner.name)` 与 `select(id, owner.name)` 两条，逐元素比对（含 `null`）。
    ///
    /// @return 每个投影形态一条动态用例
    @TestFactory
    Stream<DynamicTest> nestedProjectionMatchesJoinRows() {
        return predicates().projectionTests();
    }

    /// 嵌套 `orderBy` 的**完整序列**：3 格（join 列 asc / desc / 双键 tiebreaker），每格钉含 `null`
    /// 的整条顺序。口径与方言说明见类注释。
    ///
    /// @return 每条排序规格一条动态用例
    @TestFactory
    Stream<DynamicTest> nestedOrderByPinsCompleteSequence() {
        return new AssociationWindowProbe<>(FIXTURE_ROWS, this::query, ownerNamePath(), idRef()).orderTests();
    }

    /// 嵌套条件 + 分页：5 格（首页 / `limit` 超剩余 / `offset` 等于 total / `offset + limit` 跨尾部 /
    /// 嵌套条件后的分页），每格断 `total` 与 `data` 的主键序列。
    ///
    /// @return 每格分页一条动态用例
    @TestFactory
    Stream<DynamicTest> nestedSliceMatchesMemoryWindow() {
        return new AssociationWindowProbe<>(FIXTURE_ROWS, this::query, ownerNamePath(), idRef()).sliceTests();
    }

    /// 按 join 列聚合：分组计数两格、全表去重计数、极值两格、`null` 域两格，每格一条动态用例。
    ///
    /// 第二个取值域是关键：`min`/`max` 在全表上都被非空值占满，看不出「`null` 有没有被当成最小值」；
    /// 在「只有 `null` 关联行」的域上，`count` 必须是 `0`、`min` 必须是 `null`（不是 `0`/`""`）。
    ///
    /// @return 每个聚合形态一条动态用例
    @TestFactory
    Stream<DynamicTest> nestedAggregatesMatchMemoryOracle() {
        return new AssociationAggregateProbe<>(FIXTURE_ROWS, this::query, ownerNamePath(), idRef()).aggregateTests();
    }

    /// 谓词矩阵与投影的格子族。
    ///
    /// 每次现建而不是存字段：`ownerNamePath()` / `idRef()` 是子类实现的抽象方法，字段初始化阶段调用
    /// 会读到子类还没赋值的状态。排序/分页与聚合的格子族同理，直接在各自用例里现建。
    ///
    /// @return 谓词与投影的格子族
    private AssociationPredicateProbe<T> predicates() {
        return new AssociationPredicateProbe<>(FIXTURE_ROWS, this::query, ownerNamePath(), idRef(), ALPHA, BETA);
    }
}
