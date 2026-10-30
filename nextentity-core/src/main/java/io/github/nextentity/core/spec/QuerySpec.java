package io.github.nextentity.core.spec;

import io.github.nextentity.api.LockMode;
import io.github.nextentity.core.util.ImmutableList;
import java.util.List;
import java.util.Objects;

/// 一次查询的完整规格：编译器的唯一输入。
///
/// 它是纯值对象——不含任何后端类型，因此可以被单测逐字段断言，也可以被不同后端复用。
/// 缺省约定：`where` / `having` / `lock` 为 `null` 分别表示"没有 WHERE / HAVING / 加锁"
/// （而不是恒真节点，避免后端多渲染一层 `1=1`）。
///
/// @param entityType 查询根实体类型
/// @param selection  查询形态
/// @param where      行过滤条件，可为 null
/// @param groupBy    分组路径
/// @param having     分组后过滤条件，可为 null
/// @param orderBy    排序项
/// @param window     分页窗口
/// @param lock       锁模式，可为 null
/// @param fetch      要预加载的关联路径链（每段一层关联），不可为 null
/// @param countScope 计数针对行还是分组（见 [CountScope]）
public record QuerySpec(
        Class<?> entityType,
        Selection selection,
        Spec where,
        List<AttributePath> groupBy,
        Spec having,
        List<Ordering> orderBy,
        Window window,
        LockMode lock,
        List<AttributePath> fetch,
        CountScope countScope) {

    /// 按「数行」构造规格（历史上所有调用形态的缺省）。
    ///
    /// @param entityType 查询根实体类型
    /// @param selection  查询形态
    /// @param where      行过滤条件，可为 null
    /// @param groupBy    分组路径
    /// @param having     分组后过滤条件，可为 null
    /// @param orderBy    排序项
    /// @param window     分页窗口
    /// @param lock       锁模式，可为 null
    /// @param fetch      要预加载的关联路径链
    @SuppressWarnings("PMD.ExcessiveParameterListSix") // 转发构造器：超过 6 个参数是 record 字段表的必然投影，不引入新状态
    public QuerySpec(
            Class<?> entityType,
            Selection selection,
            Spec where,
            List<AttributePath> groupBy,
            Spec having,
            List<Ordering> orderBy,
            Window window,
            LockMode lock,
            List<AttributePath> fetch) {
        this(entityType, selection, where, groupBy, having, orderBy, window, lock, fetch, CountScope.ROWS);
    }

    /// 紧凑构造器：必填字段校验 + 不可变拷贝。
    public QuerySpec {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(groupBy, "groupBy");
        Objects.requireNonNull(orderBy, "orderBy");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(fetch, "fetch");
        Objects.requireNonNull(countScope, "countScope");
        groupBy = ImmutableList.copyOf(groupBy);
        orderBy = ImmutableList.copyOf(orderBy);
        fetch = ImmutableList.copyOf(fetch);
    }

    /// 不预加载关联的规格（历史调用形态：`fetch` 是新加的第九个分量，缺省空）。
    ///
    /// @param entityType 查询根实体类型
    /// @param selection  查询形态
    /// @param where      行过滤条件，可为 null
    /// @param groupBy    分组路径
    /// @param having     分组后过滤条件，可为 null
    /// @param orderBy    排序项
    /// @param window     分页窗口
    /// @param lock       锁模式，可为 null
    @SuppressWarnings("PMD.ExcessiveParameterListSix") // 转发构造器：同上，只是缺省 fetch/countScope 的历史入口
    public QuerySpec(
            Class<?> entityType,
            Selection selection,
            Spec where,
            List<AttributePath> groupBy,
            Spec having,
            List<Ordering> orderBy,
            Window window,
            LockMode lock) {
        this(entityType, selection, where, groupBy, having, orderBy, window, lock, List.of(), CountScope.ROWS);
    }

    /// 构造"查实体、无任何条件"的初始规格。
    ///
    /// @param entityType 查询根实体类型
    /// @return 初始规格
    public static QuerySpec of(Class<?> entityType) {
        return new QuerySpec(entityType, new EntitySelection(), null, List.of(), null, List.of(), Window.NONE, null);
    }

    /// 是否含分组、聚合或 HAVING。
    ///
    /// 它同时决定两件事：
    /// 1. 分页是否自动补主键排序——聚合/分组/HAVING 查询不补（2.2.3 `WhereImpl:258-268`）；
    /// 2. `count()` 是否拒绝——这些形态的"行数"要么语义不明确、要么要子查询计数，
    ///    宁可抛 `UnsupportedOperationException` 也不能静默给出错数。
    ///
    /// `having` 单独出现（无 `groupBy`、无聚合投影）也算：SQL 允许全表聚合后过滤，
    /// 而"只拼 where、丢掉 HAVING"的计数一定是错的。
    ///
    /// @return 含分组、聚合或 HAVING 返回 true
    public boolean aggregate() {
        if (having != null || !groupBy.isEmpty()) {
            return true;
        }
        return selection instanceof ValueSelection values
                && values.items().stream().anyMatch(AggregateProjection.class::isInstance);
    }

    /// 计数用的规格：清排序、清窗口、去锁、**清预加载**，保留 where / groupBy / having。
    ///
    /// 与 2.2.3 `WhereImpl.buildCountData` 对齐——"清 orderBy/offset/limit/lockType" 是 core 的责任，
    /// 后端只负责把同一份规格渲染成计数语句。预加载也一并清掉：`count(*)` 没有实体要填关联，
    /// 留着会让后端多 join 一层。
    ///
    /// @return 计数规格
    public QuerySpec forCount() {
        return new QuerySpec(
                entityType,
                selection,
                where,
                groupBy,
                having,
                List.of(),
                Window.NONE,
                null,
                List.of(),
                CountScope.ROWS);
    }

    /// 存在性判断用的规格：清排序、只取 1 行、去锁、清预加载，保留 where / groupBy / having。
    ///
    /// @return 存在性规格
    public QuerySpec forExists() {
        return new QuerySpec(
                entityType,
                selection,
                where,
                groupBy,
                having,
                List.of(),
                new Window(null, 1),
                null,
                List.of(),
                CountScope.ROWS);
    }

    /// 计数用的规格：有 `groupBy` 数组、否则数行。
    ///
    /// 判断放在这里而不是调用方：`count()` 的语义由规格形态决定，两个后端与单测必须得到同一个答案。
    ///
    /// @return [QuerySpec#forGroupCount]（有分组）或 [QuerySpec#forCount]（无分组）
    public QuerySpec forCounting() {
        return groupBy.isEmpty() ? forCount() : forGroupCount();
    }

    /// 存在性用的规格：有 `groupBy` 问"有没有组"、否则问"有没有行"。
    ///
    /// @return [QuerySpec#forGroupExists]（有分组）或 [QuerySpec#forExists]（无分组）
    public QuerySpec forExistence() {
        return groupBy.isEmpty() ? forExists() : forGroupExists();
    }

    /// 分组计数用的规格：数的是**组数**——`groupBy` 之后、`HAVING` 已过滤的组。
    ///
    /// 与 [QuerySpec#forCount] 的区别是投影：子查询只选分组键（`select k1, k2 ... group by k1, k2`），
    /// 外层 `count(*)` 才是组数。若沿用原来的聚合投影（`select count(...) group by ...`），
    /// 外层计数会把"每组内的行数"再数一遍，答案与 `count()` 的承诺不符。
    ///
    /// 语义与 2.2.3 `WhereImpl.buildCountData`（`select count(*) from (分组查询)`）对齐。
    ///
    /// @return 分组计数规格，`countScope()` 为 [CountScope#GROUPS]
    /// @throws IllegalArgumentException 没有 `groupBy`：没有分组键就没有"组"可数
    public QuerySpec forGroupCount() {
        if (groupBy.isEmpty()) {
            throw new IllegalArgumentException("分组计数需要至少一个 groupBy(...)：没有分组键就没有组可数");
        }
        return new QuerySpec(
                entityType,
                groupKeys(),
                where,
                groupBy,
                having,
                List.of(),
                Window.NONE,
                null,
                List.of(),
                CountScope.GROUPS);
    }

    /// 分组存在性判断用的规格：是否**存在任何分组**（`HAVING` 已生效）。
    ///
    /// 不需要子查询——按原查询渲染、限 1 行即可：有行就是有组，`HAVING` 滤空的组不会返回行。
    ///
    /// @return 分组存在性规格，`countScope()` 为 [CountScope#GROUPS]
    /// @throws IllegalArgumentException 没有 `groupBy`：存在性由 [QuerySpec#forExists] 负责
    public QuerySpec forGroupExists() {
        if (groupBy.isEmpty()) {
            throw new IllegalArgumentException("分组存在性需要至少一个 groupBy(...)：没有分组键就没有组可判断");
        }
        return new QuerySpec(
                entityType,
                selection,
                where,
                groupBy,
                having,
                List.of(),
                new Window(null, 1),
                null,
                List.of(),
                CountScope.GROUPS);
    }

    /// 分组键的投影：子查询的列集。
    private ValueSelection groupKeys() {
        List<Projection> keys = groupBy.stream()
                .map(PathProjection::new)
                .map(Projection.class::cast)
                .toList();
        return new ValueSelection(keys, false, GROUP_KEY_MAPPER);
    }

    /// 分组计数不装配行，映射器只是 `ValueSelection` 的必填占位。
    private static final RowMapper<Object> GROUP_KEY_MAPPER = row -> row.get(0);
}
