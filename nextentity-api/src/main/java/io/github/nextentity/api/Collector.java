package io.github.nextentity.api;

import java.util.List;

/// 终止操作：把查询真正执行掉。
///
/// 取行的方法（`list(int)` / `list(int, int)` / `first()`）在调用方没有显式 `orderBy` 时自动按主键升序
/// （`distinct()` 投影除外，见 [SliceStep#slice]），
/// 因此「前 N 条」「第一条」取到的行是确定的（证据：`core/builder/QueryBuilder.java:326-337`）。
/// `count()` / `exists()` 反过来不排序、不分页，也不应用 `lock(...)` 指定的锁——锁随窗口一起被清掉，
/// 只有真正取行的方法才有锁语义。
///
/// `count()` 与 `exists()` 在 `groupBy(...)` 之后另有语义（3.1.0 起）：
/// - `count()` 数的是**组数**（`having` 已生效），不是组内行数、也不是总行数；
/// - `exists()` 回答的是「是否存在任何组」（`having` 已生效），不是「是否有行」。
///
/// 两者的共同红线是**必须把 `groupBy` / `having` 一起渲染**：只渲染 `where` 会把组数答成总行数、
/// 把被 `having` 滤空的组答成「有组」，都是静默错数。
///
/// 没有 `groupBy` 的聚合形态（只有聚合投影或只有 `having`）仍然拒绝 [`UnsupportedOperationException`]：
/// 这种查询恒为一行，`count()` / `exists()` 给不出稳定答案。
///
/// @param <T> 结果类型
public interface Collector<T> {

    /// 取全部结果。
    ///
    /// @return 全部结果；无行时是空列表，不是 `null`
    List<T> list();

    /// 取前 N 条。
    ///
    /// @param limit 条数上限；`0` 表示不限行（`distinct()` 投影除外，顺序仍按主键升序），
    ///     负数抛 `IllegalArgumentException`
    /// @return 至多 `limit` 条结果；无行时是空列表，不是 `null`
    List<T> list(int limit);

    /// 从第 `offset` 行起取至多 `limit` 行。
    ///
    /// 与 [SliceStep#slice] 共用窗口与自动主键排序口径（`0` 基、负数为 IAE）；区别只在于
    /// `slice(...)` 会多跑一次计数查询并返回总数。这条重载存在的原因：[LockStep#lock] 返回的是
    /// [Collector]，没有它「带锁分页」就表达不出来，而收窄 `lock` 的返回类型会破坏 3.0.0 的兼容基线。
    ///
    /// `groupBy(...)` 之后窗口作用在**组**上（对组分页，与 `count()` 的组口径、以及 2.2.3 一致）。
    /// 本方法自己不做形态拒绝：没有 `groupBy` 的聚合形态（只有聚合投影或只有 `having`）由**后端**拒绝
    /// （[UnsupportedOperationException]，见本接口 `count()` / `exists()` 的说明），不是这条取行路径抛的。
    ///
    /// 默认实现抛 [UnsupportedOperationException]：它是**明确的契约**（"本实现不支持带窗口分页"），
    /// 不是能用的兜底——刻意不做内存内的 skip/limit，那会把分页静默退化成全表加载。
    /// 3.1.0 起内置实现（`QueryBuilder`）已覆盖，自定义 `Collector` 实现需要自行覆盖。
    ///
    /// @param offset 0 基行偏移，不得为负
    /// @param limit  页大小，不得为负；`0` 表示从 `offset` 起不限行数
    /// @return 该页的行；无行时是空列表，不是 `null`
    /// @throws IllegalArgumentException `offset` 或 `limit` 为负数
    /// @throws UnsupportedOperationException 本实现不支持带窗口分页
    default List<T> list(int offset, int limit) {
        throw new UnsupportedOperationException("该 Collector 实现未支持带窗口分页；3.1.0 起内置实现已支持，自定义实现请覆盖本方法");
    }

    /// 取第一条，无结果返回 `null`。
    ///
    /// @return 第一条；无结果时是 `null`，不抛异常
    T first();

    /// 取唯一一条，无结果返回 `null`。
    ///
    /// @return 唯一一条；无结果时是 `null`，不抛异常
    /// @throws IllegalStateException 命中多于一条（消息固定 `found more than one result, expected one`）
    T single();

    /// 计数。结果含义随查询形态变化：
    ///
    /// - 普通查询：满足条件的行数；
    /// - `distinct()`：**去重后的投影值个数**（`count(DISTINCT 投影)`，证据：
    ///   `jpa/exec/JpaExecutor.java:71-81`）；
    /// - `groupBy(...)`：**组数**（`having` 已生效），等价于对分组结果再做一次计数。
    ///
    /// @return 行数、去重投影值个数，或组数（多列去重不支持，抛 `UnsupportedOperationException`）
    /// @throws UnsupportedOperationException `distinct()` 多列去重；没有 `groupBy` 的聚合形态
    ///     （只有聚合投影或只有 `having`）：它恒为一行，给不出稳定答案，因此直接拒绝而不是给出一个错答案
    long count();

    /// 过滤后是否存在任何行（`groupBy(...)` 之后：是否存在任何组）。
    ///
    /// 普通查询只看条件：没有命中行即为 `false`（与 2.2.3 `WhereImpl.java:134-148` 的
    /// `SELECT_ANY` + limit 1 逐项一致）。
    ///
    /// `groupBy(...)` 之后看的是**组**：`having` 把所有组都过滤掉时为 `false`，因此 `groupBy` /
    /// `having` 必须一起渲染——只渲染 `where` 会给出与查询**相反**的布尔值。
    ///
    /// @return 命中至少一行（分组时：至少存在一个组）返回 `true`
    /// @throws UnsupportedOperationException 没有 `groupBy` 的聚合形态（只有聚合投影或只有 `having`）：
    ///     它恒有一行，回答没有信息量
    boolean exists();
}
