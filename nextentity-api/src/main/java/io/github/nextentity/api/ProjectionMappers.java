package io.github.nextentity.api;

import io.github.nextentity.api.model.Tuple;
import io.github.nextentity.api.model.Tuple4;
import io.github.nextentity.api.model.Tuple5;
import io.github.nextentity.api.model.Tuple6;
import java.util.List;
import java.util.function.Function;

/// 四列到六列投影的工厂：把多列投影包成 [ProjectionMapper]，结果为具名的 [Tuple4]~[Tuple6]。
///
/// 为什么不给 `select(...)` 再加四列以上的重载：每多一列就要多一个重载，`SelectStep` 的方法数会随列数增长，
/// 而 `API_INTERFACES_STAY_NARROW` 上限是 6。静态工厂把这一族挪出步骤接口，调用点仍拿得到**带类型参数**
/// 的元组：`Tuple4<Long, String, BigDecimal, Instant>` 的 `get0()` 直接是 `Long`，列序写反会让元组的
/// 类型参数跟着变、从而编译不过。唯一那一次「按下标取值再转型」被关在本类里一处。
///
/// 封顶六列是项目规则 `ExcessiveParameterListSix` 的直接后果（参数上限 6，PMD 默认 10 被刻意收紧）：
/// 工厂方法的参数个数就是元组宽度，七列起没有合规写法。需要更多列时用自定义 [ProjectionMapper]，
/// 由 `map(Tuple)` 自己转型（那一层无法静态检查）。
///
/// 三列不走这里：`SelectStep#select(Expression, Expression, Expression)` 已给 [Tuple3]，避免两个入口。
///
/// 用法：`query().project(ProjectionMappers.select(e1, e2, e3, e4)).list()`，结果是
/// `List<Tuple4<A, B, C, D>>`，列序即实参顺序。
@SuppressWarnings("PMD.CouplingBetweenObjects") // 元组工厂刻意把 Tuple4~Tuple6 整族收在一处：拆开就失去「一个地方看全列宽对应关系」的唯一好处
public final class ProjectionMappers {

    private ProjectionMappers() {}

    /// 投影四列，结果为 [Tuple4]。
    ///
    /// @param first 第一列，非 `null`
    /// @param second 第二列，非 `null`
    /// @param third 第三列，非 `null`
    /// @param fourth 第四列，非 `null`
    /// @return 映射器，交给 `SelectStep#project(...)` 使用
    @SuppressWarnings("unchecked") // 按下标取列后转成各列类型：元组装配的唯一定点，正确性由列序约定保证
    public static <T, A, B, C, D> ProjectionMapper<T, Tuple4<A, B, C, D>> select(
            Expression<T, A> first, Expression<T, B> second, Expression<T, C> third, Expression<T, D> fourth) {
        return of(
                List.of(first, second, third, fourth),
                row -> new Tuple4<>((A) row.get(0), (B) row.get(1), (C) row.get(2), (D) row.get(3)));
    }

    /// 投影五列，结果为 [Tuple5]。
    ///
    /// @param first 第一列，非 `null`
    /// @param second 第二列，非 `null`
    /// @param third 第三列，非 `null`
    /// @param fourth 第四列，非 `null`
    /// @param fifth 第五列，非 `null`
    /// @return 映射器，交给 `SelectStep#project(...)` 使用
    @SuppressWarnings("unchecked") // 按下标取列后转成各列类型：元组装配的唯一定点，正确性由列序约定保证
    public static <T, A, B, C, D, E> ProjectionMapper<T, Tuple5<A, B, C, D, E>> select(
            Expression<T, A> first,
            Expression<T, B> second,
            Expression<T, C> third,
            Expression<T, D> fourth,
            Expression<T, E> fifth) {
        return of(
                List.of(first, second, third, fourth, fifth),
                row -> new Tuple5<>((A) row.get(0), (B) row.get(1), (C) row.get(2), (D) row.get(3), (E) row.get(4)));
    }

    /// 投影六列，结果为 [Tuple6]。
    ///
    /// @param first 第一列，非 `null`
    /// @param second 第二列，非 `null`
    /// @param third 第三列，非 `null`
    /// @param fourth 第四列，非 `null`
    /// @param fifth 第五列，非 `null`
    /// @param sixth 第六列，非 `null`
    /// @return 映射器，交给 `SelectStep#project(...)` 使用
    @SuppressWarnings("unchecked") // 按下标取列后转成各列类型：元组装配的唯一定点，正确性由列序约定保证
    public static <T, A, B, C, D, E, F> ProjectionMapper<T, Tuple6<A, B, C, D, E, F>> select(
            Expression<T, A> first,
            Expression<T, B> second,
            Expression<T, C> third,
            Expression<T, D> fourth,
            Expression<T, E> fifth,
            Expression<T, F> sixth) {
        return of(
                List.of(first, second, third, fourth, fifth, sixth),
                row -> new Tuple6<>((A) row.get(0), (B) row.get(1), (C) row.get(2), (D) row.get(3), (E) row.get(4), (F)
                        row.get(5)));
    }

    /// 把列集合与映射函数包成无状态映射器：`expressions()` 返回同一个不可变列表，可跨查询复用。
    private static <T, R> ProjectionMapper<T, R> of(List<Expression<T, ?>> expressions, Function<Tuple, R> map) {
        return new ProjectionMapper<>() {
            @Override
            public List<Expression<T, ?>> expressions() {
                return expressions;
            }

            @Override
            public R map(Tuple row) {
                return map.apply(row);
            }
        };
    }
}
