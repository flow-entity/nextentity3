package io.github.nextentity.core.spec;

import io.github.nextentity.api.LockMode;
import java.util.List;
import java.util.Objects;

/// 一次查询的完整规格：编译器的唯一输入。
///
/// 它是纯值对象——不含任何后端类型，因此可以被单测逐字段断言，也可以被不同后端复用。
/// 缺省约定：`where` 为 `null` 表示"没有 WHERE"（而不是恒真节点，避免后端多渲染一层 `1=1`）；
/// `lock` 为 `null` 表示不加锁。
///
/// @param entityType 查询根实体类型，非 `null`
/// @param selection  查询形态，非 `null`
/// @param where      条件，可为 `null`：`null` 表示没有 WHERE
/// @param groupBy    分组路径，空列表表示不分组
/// @param orderBy    排序项，按列表顺序渲染成多列排序
/// @param window     分页窗口，非 `null`：无窗口用 `Window.NONE`
/// @param lock       锁模式，可为 `null`：`null` 表示不加锁
public record QuerySpec(
        Class<?> entityType,
        Selection selection,
        Spec where,
        List<AttributePath> groupBy,
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
        groupBy = List.copyOf(groupBy);
        orderBy = List.copyOf(orderBy);
    }

    /// 构造"查实体、无任何条件"的初始规格。
    ///
    /// @param entityType 查询根实体类型，非 `null`
    /// @return 查实体、无 where、无分组/排序、无窗口、无锁的初始规格
    public static QuerySpec of(Class<?> entityType) {
        return new QuerySpec(entityType, new EntitySelection(), null, List.of(), List.of(), Window.NONE, null);
    }

    /// 是否含分组或聚合（2.2.3 据此决定"分页自动加主键排序"是否生效，见 `WhereImpl:258-268`）。
    ///
    /// @return 有分组列或聚合投影返回 `true`（决定分页是否自动补主键排序）
    public boolean aggregate() {
        if (!groupBy.isEmpty()) {
            return true;
        }
        return selection instanceof ValueSelection values
                && values.items().stream().anyMatch(AggregateProjection.class::isInstance);
    }

    /// 计数用的规格：清排序、清窗口、去锁。
    ///
    /// 与 2.2.3 `WhereImpl.buildCountData` 对齐——"清 orderBy/offset/limit/lockType" 是 core 的责任，
    /// 后端只负责把同一份规格渲染成计数语句。
    ///
    /// @return 新规格（原规格不变）：清排序、清窗口、去锁
    public QuerySpec forCount() {
        return new QuerySpec(entityType, selection, where, groupBy, List.of(), Window.NONE, null);
    }

    /// 存在性判断用的规格：清排序、只取 1 行、去锁（对齐 2.2.3 `WhereImpl:134-147`）。
    ///
    /// @return 新规格（原规格不变）：清排序、限 1 行、去锁
    public QuerySpec forExists() {
        return new QuerySpec(entityType, selection, where, groupBy, List.of(), new Window(null, 1), null);
    }
}
