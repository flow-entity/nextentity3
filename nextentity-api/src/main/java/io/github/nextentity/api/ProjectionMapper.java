package io.github.nextentity.api;

import io.github.nextentity.api.model.Tuple;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/// 显式投影映射器：声明要查哪些表达式，并把查回来的行映射成调用方类型。
///
/// 「查什么」与「怎么造」由同一个对象拥有，因此两者不可能被传错配。它只通过 [SelectStep#project(ProjectionMapper)]
/// 参与查询，别的入口不会用到它。
///
/// 契约：
/// - 实现必须**无状态**，可作单例（如 Spring bean）跨查询复用：[#map(Tuple)] 不得依赖上一次调用留下的状态；
/// - [#expressions()] 每次 `project(...)` 调用求值一次，不跨查询缓存；实现方自己缓存返回值
///   不会改变框架行为（框架不依赖它是否被重复调用），但也换不来任何保证；
/// - 取值靠**位置**，没有列别名：表达式顺序即 [#map(Tuple)] 入参的列序。
///
/// 容易误用的语义：表达式含聚合（`sum`/`max`/`count` 等）却没有 `groupBy` 时，SQL 退化成
/// 「整表一个分组」，查询**恒返回 1 行**（空表上也返回一行：`count` 给 0，其余给 `null`；
/// 有 `having` 且不满足时该行被过滤掉）。这是合法形态，框架不拦：
/// 「按 shopId 分组求和」漏写 `groupBy` 不会报错，只会拿到一行总数。
///
/// 非目标：
/// - 不做反射/注解自动映射（`project(OrderReport.class)` 那类），映射代码由调用方手写，
///   以便编译期检查与 IDE 跳转；
/// - 不做列别名，需要可读性时从表达式推导显示名，不进本接口。
///
/// 因为顺序是唯一的列标识，实现方应把 `private static final int IDX_*` 下标常量与 [#expressions()]
/// 相邻声明，让「列顺序 ↔ 取值下标」的错位在代码审查里一眼可见。
///
/// @param <T> 根实体类型
/// @param <R> 结果类型
public interface ProjectionMapper<T, R> {

    /// 要查的表达式，顺序即结果列顺序。
    ///
    /// 可接受的形态：属性列写成 `Path.of(Order::getId)` 或裸方法引用 `Order::getId`（`PathRef` 也是
    /// `Expression`）；聚合/算术结果（`api.Op` 的实现）写成 `Path.of(Order::getAmount).sum()` 这类。
    ///
    /// @return 非空列表；元素非 `null` 且每一项都可被后端解释
    List<Expression<T, ?>> expressions();

    /// 换结果类型：列集不变，只把 [map(Tuple)] 的产物再过一道函数。
    ///
    /// 派生出的映射器仍然无状态、可跨查询复用：[expressions()] 原样委托给本对象，`project(...)` 仍只求值一次。
    ///
    /// 为什么不叫 `map`：本接口的 [map(Tuple)] 是 SPI（实现方必须给出「行 → R」），而这里是「R → S」的组合子；
    /// 同名会让两件毫不相干的事共用一个词。
    ///
    /// @param after 结果转换函数，非 `null`
    /// @param <S> 新的结果类型
    /// @return 派生映射器：同一列集，结果是 `after.apply(map(row))`
    default <S> ProjectionMapper<T, S> andThen(Function<? super R, ? extends S> after) {
        Objects.requireNonNull(after, "after");
        return new ProjectionMapper<>() {
            @Override
            public List<Expression<T, ?>> expressions() {
                return ProjectionMapper.this.expressions();
            }

            @Override
            public S map(Tuple row) {
                return after.apply(ProjectionMapper.this.map(row));
            }
        };
    }

    /// 把一行查询结果映射成 `R`。
    ///
    /// @param row 按 [#expressions()] 顺序装配的行，非 `null`；列数等于 `expressions().size()`，
    ///     单列投影同样是长度为 1 的 `Tuple`（不是裸值）
    /// @return 映射结果，可为 `null`
    R map(Tuple row);
}
