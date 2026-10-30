package io.github.nextentity.api;

import java.util.Collection;
import java.util.List;

/// 预加载步骤：声明本次查询要一并取回的关联，避免拿到实体后再逐条查关联（N+1）。
///
/// 它只声明"要预加载哪些关联"，具体怎么取（`join fetch`、批量二次查询、实体图）由后端决定；
/// 关联为可选时应当仍能取到没有关联的行，所以预加载不该把结果集变成内连接——语义与
/// `left join fetch` 对齐。
///
/// 调用位置：`query().fetch(...)` 之后得到 [BaseWhereStep]，可以继续 `where` / `orderBy` /
/// 分页 / 加锁；**不能**在 `select(...)` 之后调（投影查询的行不是实体，没有关联可填）。
///
/// ## 使用示例
///
/// ```java
/// // 预加载单个关联
/// List<Employee> employees = repository.query()
///     .fetch(Employee::getDepartment)
///     .where(Employee::getActive).eq(true)
///     .list();
///
/// // 预加载多个关联
/// List<Employee> employees = repository.query()
///     .fetch(Employee::getDepartment, Employee::getManager)
///     .list();
///
/// // 预加载嵌套关联
/// List<Employee> employees = repository.query()
///     .fetch(Path.of(Employee::getDepartment).get(Department::getManager))
///     .list();
/// ```
///
/// @param <T> 实体类型
/// @see EntityQuery
public interface FetchStep<T> extends BaseWhereStep<T, T> {

    /// 预加载给定路径链对应的关联。
    ///
    /// 路径的每一段都是一层关联；一段路径表示"沿这条链逐段预加载"。
    /// 重复的路径只生效一次。
    ///
    /// @param paths 路径链，不能为 null（空集合等价于不预加载）
    /// @return 后续步骤
    /// @throws NullPointerException 集合或其元素为 null
    BaseWhereStep<T, T> fetch(Collection<? extends Path<T, ?>> paths);

    /// 预加载一条路径链对应的关联。
    ///
    /// @param path 路径链
    /// @return 后续步骤
    default BaseWhereStep<T, T> fetch(Path<T, ?> path) {
        return fetch(List.<Path<T, ?>>of(path));
    }

    /// 预加载两条路径链对应的关联。
    ///
    /// @param p0 第一条路径链
    /// @param p1 第二条路径链
    /// @return 后续步骤
    default BaseWhereStep<T, T> fetch(Path<T, ?> p0, Path<T, ?> p1) {
        return fetch(List.<Path<T, ?>>of(p0, p1));
    }

    /// 预加载三条路径链对应的关联。
    ///
    /// @param p0 第一条路径链
    /// @param p1 第二条路径链
    /// @param p2 第三条路径链
    /// @return 后续步骤
    default BaseWhereStep<T, T> fetch(Path<T, ?> p0, Path<T, ?> p1, Path<T, ?> p2) {
        return fetch(List.<Path<T, ?>>of(p0, p1, p2));
    }

    /// 预加载一条方法引用对应的关联。
    ///
    /// @param path 方法引用形态的路径
    /// @return 后续步骤
    default BaseWhereStep<T, T> fetch(PathRef<T, ?> path) {
        return fetch(Path.of(path));
    }

    /// 预加载两条方法引用对应的关联。
    ///
    /// @param p0 第一个方法引用
    /// @param p1 第二个方法引用
    /// @return 后续步骤
    default BaseWhereStep<T, T> fetch(PathRef<T, ?> p0, PathRef<T, ?> p1) {
        return fetch(Path.of(p0), Path.of(p1));
    }
}
