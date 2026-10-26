package io.github.nextentity.api;

import java.util.List;

/// 终止操作：把查询真正执行掉。
///
/// 取行的方法（`list(int)` / `first()`）在调用方没有显式 `orderBy` 时自动按主键升序
/// （`distinct()` 投影除外，见 [SliceStep#slice]），
/// 因此「前 N 条」「第一条」取到的行是确定的（证据：`core/builder/QueryBuilder.java:326-337`）。
/// `count()` / `exists()` 反过来不排序、不分页，也不应用 `lock(...)` 指定的锁——锁随窗口一起被清掉，
/// 只有真正取行的方法才有锁语义。
///
/// `count()` 与 `exists()` 对分组/聚合规格都直接拒绝。理由不同，结论一致，不要为「让两个终止符统一」
/// 而放宽任何一侧：
/// - `count()` 只渲染 `where` 会把分组数当成总行数，属于静默错数；
/// - `exists()` 只渲染 `where` 会把被 `having` 滤空的组答成「有行」，结论正好相反。
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
    /// - 带 `groupBy` / `having` 或聚合投影：不支持。
    ///
    /// @return 行数，或 `distinct()` 时的去重投影值个数（多列去重不支持，抛 `UnsupportedOperationException`）
    /// @throws UnsupportedOperationException 带 `groupBy` / `having` 或聚合投影的查询：照常数行会把
    ///     「分组数」数成「总行数」，属于静默错数，因此直接拒绝而不是给出一个错答案
    long count();

    /// 过滤后是否存在任何行。
    ///
    /// 普通查询只看条件：没有命中行即为 `false`（与 2.2.3 `WhereImpl.java:134-148` 的
    /// `SELECT_ANY` + limit 1 逐项一致）。
    ///
    /// 带 `groupBy` / `having` 或聚合投影的查询**不支持**：`having` 能把所有组都过滤掉，只渲染
    /// `where` 会给出与查询**相反**的布尔值，因此直接拒绝而不是静默近似（证据：
    /// `jpa/exec/JpaExecutor.java:100-103`）。与 `count()` 的处理一致。
    ///
    /// @return 命中至少一行返回 `true`
    /// @throws UnsupportedOperationException 带 `groupBy` / `having` 或聚合投影的查询：静默近似会返回相反结论
    boolean exists();
}
