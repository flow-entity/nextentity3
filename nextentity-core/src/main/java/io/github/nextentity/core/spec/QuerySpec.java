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
public record QuerySpec(
        Class<?> entityType,
        Selection selection,
        Spec where,
        List<AttributePath> groupBy,
        Spec having,
        List<Ordering> orderBy,
        Window window,
        LockMode lock) {

    /// 紧凑构造器：必填字段校验 + 不可变拷贝。
    public QuerySpec {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(groupBy, "groupBy");
        Objects.requireNonNull(orderBy, "orderBy");
        Objects.requireNonNull(window, "window");
        groupBy = ImmutableList.copyOf(groupBy);
        orderBy = ImmutableList.copyOf(orderBy);
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

    /// 计数用的规格：清排序、清窗口、去锁，**保留** where / groupBy / having。
    ///
    /// 与 2.2.3 `WhereImpl.buildCountData` 对齐——"清 orderBy/offset/limit/lockType" 是 core 的责任，
    /// 后端只负责把同一份规格渲染成计数语句。
    ///
    /// @return 计数规格
    public QuerySpec forCount() {
        return new QuerySpec(entityType, selection, where, groupBy, having, List.of(), Window.NONE, null);
    }

    /// 存在性判断用的规格：清排序、只取 1 行、去锁，**保留** where / groupBy / having。
    ///
    /// @return 存在性规格
    public QuerySpec forExists() {
        return new QuerySpec(entityType, selection, where, groupBy, having, List.of(), new Window(null, 1), null);
    }
}
