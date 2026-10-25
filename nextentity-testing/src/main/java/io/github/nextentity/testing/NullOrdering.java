package io.github.nextentity.testing;

import java.util.Comparator;

/// 升序时 `null` 排在**前**还是**后**——这是后端方言特性，不是实现细节。
///
/// 两族方言的默认行为正好相反：
/// - MySQL（以及 H2 的 `MODE=MySQL`）：`ASC` 时 `null` 在前，`DESC` 时在后；
/// - PostgreSQL（以及 H2 `MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH`）：`ASC` 时 `null` 在后，`DESC` 时在前。
///
/// 内存 oracle 必须按**被测后端真实语义**排，否则「差分」比的是两套不同的排序定义：红的时候指向的是
/// oracle 错，不是实现错。所以这个选择不藏在基类里，而是显式声明在
/// [ConsistencyTestBase#nullOrdering()] 与 [AssociationDifferentialTest] 的构造器参数上，
/// 由后端适配器按方言覆盖；默认取 MySQL 语义（本项目当前唯一的第一方后端）。
///
/// **只声明升序一侧**：降序一律用 [Comparator#reversed] 取反，取反后「升序 null 在前」自然变成
/// 「降序 null 在后」，两族方言都成立，不用维护第二套规则。
public enum NullOrdering {

    /// 升序时 `null` 在前（MySQL 默认）。
    NULLS_FIRST,

    /// 升序时 `null` 在后（PostgreSQL 默认）。
    NULLS_LAST;

    /// 给可空键套上本策略的升序比较器。
    ///
    /// 不约束 `V extends Comparable`：本方法只把 `natural` 包一层 `nullsFirst`/`nullsLast`，
    /// 完全不比较非空值，约束只会让调用点（`Comparator.naturalOrder()` 配 `Comparator<T>`）
    /// 的类型推断撞上两个不同的上限。
    ///
    /// @param natural 非空值的自然序
    /// @param <V>     键类型
    /// @return 按本策略放置 `null` 的升序比较器
    public <V> Comparator<V> ascending(Comparator<V> natural) {
        return this == NULLS_FIRST ? Comparator.nullsFirst(natural) : Comparator.nullsLast(natural);
    }
}
