package io.github.nextentity.core.builder;

import io.github.nextentity.api.Expression;
import io.github.nextentity.api.PathOperator;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.Logical;
import io.github.nextentity.core.spec.Spec;
import java.util.Arrays;
import java.util.Collection;
import java.util.function.Function;
import java.util.function.Supplier;

/// 查询链上的属性算子：`query.where(X::getY)` 之后的 `eq/gt/in/...`。
///
/// 与 `api.Path` 上的同名算子不同，这里的返回值是**查询步骤本身**（`B`），
/// 每调用一次就往 WHERE 里追加一个节点；`*IfNotNull(null)` 走 `skip`，一个节点都不追加
/// （2.2.3：空值短路 = 条件跳过，不是"永不匹配"）。
///
/// @param <T> 根实体类型
/// @param <U> 属性值类型
/// @param <B> 查询步骤类型
@SuppressWarnings("PMD.TooManyMethods") // 方法数由 api.PathOperator 接口面决定，不是上帝类
final class PathStepOperator<T, U, B> implements PathOperator<T, U, B> {

    private final AttributePath path;
    private final Function<Spec, B> next;
    private final Supplier<B> skip;

    PathStepOperator(AttributePath path, Function<Spec, B> next, Supplier<B> skip) {
        this.path = path;
        this.next = next;
        this.skip = skip;
    }

    @Override
    public B eq(U value) {
        return next.apply(Specs.comparison(path, ComparisonOperator.EQ, Literal.of(value)));
    }

    @Override
    public B eq(Expression<T, U> other) {
        return next.apply(Specs.comparison(path, ComparisonOperator.EQ, OpInterpreter.operand(other)));
    }

    @Override
    public B eqIfNotNull(U value) {
        return value == null ? skip.get() : eq(value);
    }

    @Override
    public B ne(U value) {
        return next.apply(Specs.comparison(path, ComparisonOperator.NE, Literal.of(value)));
    }

    @Override
    public B ne(Expression<T, U> other) {
        return next.apply(Specs.comparison(path, ComparisonOperator.NE, OpInterpreter.operand(other)));
    }

    @Override
    public B ge(U value) {
        return next.apply(Specs.comparison(path, ComparisonOperator.GE, Literal.of(value)));
    }

    @Override
    public B ge(Expression<T, U> other) {
        return next.apply(Specs.comparison(path, ComparisonOperator.GE, OpInterpreter.operand(other)));
    }

    @Override
    public B gt(U value) {
        return next.apply(Specs.comparison(path, ComparisonOperator.GT, Literal.of(value)));
    }

    @Override
    public B gt(Expression<T, U> other) {
        return next.apply(Specs.comparison(path, ComparisonOperator.GT, OpInterpreter.operand(other)));
    }

    @Override
    public B le(U value) {
        return next.apply(Specs.comparison(path, ComparisonOperator.LE, Literal.of(value)));
    }

    @Override
    public B le(Expression<T, U> other) {
        return next.apply(Specs.comparison(path, ComparisonOperator.LE, OpInterpreter.operand(other)));
    }

    @Override
    public B lt(U value) {
        return next.apply(Specs.comparison(path, ComparisonOperator.LT, Literal.of(value)));
    }

    @Override
    public B lt(Expression<T, U> other) {
        return next.apply(Specs.comparison(path, ComparisonOperator.LT, OpInterpreter.operand(other)));
    }

    @Override
    public B geIfNotNull(U value) {
        return value == null ? skip.get() : ge(value);
    }

    @Override
    public B gtIfNotNull(U value) {
        return value == null ? skip.get() : gt(value);
    }

    @Override
    public B leIfNotNull(U value) {
        return value == null ? skip.get() : le(value);
    }

    @Override
    public B ltIfNotNull(U value) {
        return value == null ? skip.get() : lt(value);
    }

    @Override
    public B isNull() {
        return next.apply(Specs.nullCheck(path, false));
    }

    @Override
    public B isNotNull() {
        return next.apply(Specs.nullCheck(path, true));
    }

    @Override
    public B in(Collection<? extends U> values) {
        return next.apply(Specs.membership(path, values, false));
    }

    @Override
    @SafeVarargs
    public final B in(U... values) {
        return next.apply(Specs.membership(path, Arrays.asList(values), false));
    }

    @Override
    public B notIn(Collection<? extends U> values) {
        return next.apply(Specs.membership(path, values, true));
    }

    @Override
    @SafeVarargs
    public final B notIn(U... values) {
        return next.apply(Specs.membership(path, Arrays.asList(values), true));
    }

    @Override
    public B inIfNotNull(Collection<? extends U> values) {
        return values == null ? skip.get() : in(values);
    }

    @Override
    public B between(U from, U to) {
        return next.apply(Specs.between(path, Literal.of(from), Literal.of(to)));
    }

    @Override
    public B between(Expression<T, U> from, Expression<T, U> to) {
        return next.apply(Specs.between(path, OpInterpreter.operand(from), OpInterpreter.operand(to)));
    }

    @Override
    public B notBetween(U from, U to) {
        return next.apply(Logical.not(Specs.between(path, Literal.of(from), Literal.of(to))));
    }
}
