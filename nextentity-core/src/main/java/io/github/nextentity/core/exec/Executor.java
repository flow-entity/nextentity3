package io.github.nextentity.core.exec;

import java.util.List;

/// 执行器接缝：跑后端语句并返回结果。
///
/// 与 {@link io.github.nextentity.core.compile.Compiler} 成对出现：编译器只渲染，执行器只执行。
/// 只保留三个方法——`first` / `single` / `slice` 由 core 的查询构建器用
/// `list` + `count` 组合出来（2.2.3 里同样是 default 方法），后端因此不必为每个终止符各写一遍。
///
/// @param <S> 后端语句类型
public interface Executor<S> {

    /// 执行查询并取回结果。
    ///
    /// 结果元素类型由规格里的查询形态决定：查实体 → 实体；单投影项 → 该值；
    /// 多投影项 → 元组。后端只负责按形态装配，不做业务语义。
    ///
    /// @param query 后端语句，非 `null`
    /// @param <R>   结果元素类型
    /// @return 结果列表；无行时是空列表，不是 `null`
    <R> List<R> list(S query);

    /// 计数：同一份规格（已由 `QuerySpec.forCount()` 清排序/窗口/锁）渲染成计数语句。
    ///
    /// @param query 后端语句，非 `null`
    /// @return 行数；单列 `distinct` 规格返回去重后的投影值个数
    long count(S query);

    /// 存在性：同一份规格（已由 `QuerySpec.forExists()` 清排序并限 1 行）。
    ///
    /// @param query 后端语句，非 `null`
    /// @return 命中至少一行返回 `true`
    boolean exists(S query);
}
