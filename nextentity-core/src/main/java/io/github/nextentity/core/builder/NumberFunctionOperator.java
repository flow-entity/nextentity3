package io.github.nextentity.core.builder;

import io.github.nextentity.api.Expression;
import io.github.nextentity.api.NumberOperator;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.Logical;
import io.github.nextentity.core.spec.Operand;
import io.github.nextentity.core.spec.Spec;
import java.util.function.Function;
import java.util.function.Supplier;

/// 查询链上的数字算子：`where(X::getName).length()` 之后的 `gt/between/...`。
///
/// 主语是数字操作数（当前唯一来源是字符串长度函数节点），因此只提供比较、区间与空值短路三族；
/// `in` / `isNull` 的语义针对实体属性，对函数结果没有确定的 SQL 对应物，`api.NumberOperator` 故意不接这两族。
///
/// @param <T> 根实体类型
/// @param <B> 查询步骤类型
@SuppressWarnings("PMD.TooManyMethods") // 方法数由 api.NumberOperator 继承的接口面决定
final class NumberFunctionOperator<T, B> implements NumberOperator<T, B> {

    private final Operand subject;
    private final Function<Spec, B> next;
    private final Supplier<B> skip;

    NumberFunctionOperator(Operand subject, Function<Spec, B> next, Supplier<B> skip) {
        this.subject = subject;
        this.next = next;
        this.skip = skip;
    }

    @Override
    public B eq(Integer value) {
        return next.apply(Specs.comparison(subject, ComparisonOperator.EQ, Literal.of(value)));
    }

    @Override
    public B eqIfNotNull(Integer value) {
        return value == null ? skip.get() : eq(value);
    }

    @Override
    public B ne(Integer value) {
        return next.apply(Specs.comparison(subject, ComparisonOperator.NE, Literal.of(value)));
    }

    @Override
    public B ge(Integer value) {
        return next.apply(Specs.comparison(subject, ComparisonOperator.GE, Literal.of(value)));
    }

    @Override
    public B gt(Integer value) {
        return next.apply(Specs.comparison(subject, ComparisonOperator.GT, Literal.of(value)));
    }

    @Override
    public B le(Integer value) {
        return next.apply(Specs.comparison(subject, ComparisonOperator.LE, Literal.of(value)));
    }

    @Override
    public B lt(Integer value) {
        return next.apply(Specs.comparison(subject, ComparisonOperator.LT, Literal.of(value)));
    }

    @Override
    public B geIfNotNull(Integer value) {
        return value == null ? skip.get() : ge(value);
    }

    @Override
    public B gtIfNotNull(Integer value) {
        return value == null ? skip.get() : gt(value);
    }

    @Override
    public B leIfNotNull(Integer value) {
        return value == null ? skip.get() : le(value);
    }

    @Override
    public B ltIfNotNull(Integer value) {
        return value == null ? skip.get() : lt(value);
    }

    @Override
    public B between(Integer from, Integer to) {
        return next.apply(Specs.between(subject, Literal.of(from), Literal.of(to)));
    }

    @Override
    public B between(Expression<T, Integer> from, Expression<T, Integer> to) {
        return next.apply(Specs.between(subject, OpInterpreter.operand(from), OpInterpreter.operand(to)));
    }

    @Override
    public B notBetween(Integer from, Integer to) {
        return next.apply(Logical.not(Specs.between(subject, Literal.of(from), Literal.of(to))));
    }
}
