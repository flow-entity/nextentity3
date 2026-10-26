package io.github.nextentity.core.exec;

import java.util.List;

/// 执行器接缝：跑后端语句并返回结果。
///
/// 与 {@link io.github.nextentity.core.compile.Compiler} 成对出现：编译器只渲染，执行器只执行。
/// 只保留三个方法——`first` / `single` / `slice` 由 core 的查询构建器用
/// `list` + `count` 组合出来（2.2.3 里同样是 default 方法），后端因此不必为每个终止符各写一遍。
///
/// **职责边界**：本接缝负责「把语句跑出后端的原始行」，投影装配（原始行 → 结果对象）**不在**这一层，
/// 见 [#list(Object)]。这样新增后端只需实现「怎么跑」，装配规则仍只有 core 的一份。
///
/// @param <S> 后端语句类型
public interface Executor<S> {

    /// 执行查询并交回**后端原始行**。
    ///
    /// **本层不做投影装配**。`Selection`（实体 / 值查询、列集、`rowMapper`）的解读统一在
    /// [io.github.nextentity.core.builder.RowAssembler]，由 `QueryBuilder` 在拿到行之后调用一次
    /// （`QueryBuilder#fetch` 是唯一调用点）。
    ///
    /// 行形态：实体查询交实体本身；值查询交**列数等于投影项个数**的行——单列可以是裸值或长度 1 的
    /// `Object[]`（装配层两种都认），多列必须是长度与投影列数相等的 `Object[]`；长度不符时装配层
    /// 抛 `IllegalStateException`（不猜形态、不静默补 `null`）。**不得**自行把行装配成 `Tuple`
    /// 或投影值，**不得**为多列去拼接元组。
    ///
    /// 这条边界的理由：装配规则与「投影了几列」绑在一起，只有 `ValueSelection` 知道列数；
    /// 若每个后端各装配一遍，同一份列集就会在不同后端产出不同对象。所以装配是**一次**的，
    /// 位置固定在 core 的 builder，而不是接口的每个实现里。
    ///
    /// @param query 后端语句，非 `null`
    /// @param <R>   元素类型就由**调用点**声明：实体查询是实体，值查询是后端的行形态（装配前）
    /// @return 行列表；无行时是空列表，不是 `null`
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
