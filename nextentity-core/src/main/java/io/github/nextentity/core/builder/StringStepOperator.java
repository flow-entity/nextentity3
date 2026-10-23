package io.github.nextentity.core.builder;

import io.github.nextentity.api.StringOperator;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.Spec;
import java.util.Arrays;
import java.util.Collection;
import java.util.function.Function;
import java.util.function.Supplier;

/// 查询链上的字符串算子：`query.where(X::getName)` 之后的 `contains/...`。
///
/// `CONTAINS` 系列在这里展开成 `%value%` 的 LIKE 模式（2.2.3 `ExpressionBuilder:520-522`），
/// 通配符只在这一处拼；`like` / `likeIfNotNull` 原样透传调用方自带的模式。
///
/// @param <T> 根实体类型
/// @param <B> 查询步骤类型
@SuppressWarnings("PMD.TooManyMethods") // 方法数由 api.StringOperator 接口面决定
final class StringStepOperator<T, B> implements StringOperator<T, B> {

    private final AttributePath path;
    private final Function<Spec, B> next;
    private final Supplier<B> skip;

    StringStepOperator(AttributePath path, Function<Spec, B> next, Supplier<B> skip) {
        this.path = path;
        this.next = next;
        this.skip = skip;
    }

    @Override
    public B eq(String value) {
        return next.apply(Specs.comparison(path, ComparisonOperator.EQ, Literal.of(value)));
    }

    @Override
    public B eqIfNotNull(String value) {
        return value == null ? skip.get() : eq(value);
    }

    @Override
    public B eqIfNotEmpty(String value) {
        return isEmpty(value) ? skip.get() : eq(value);
    }

    @Override
    public B ne(String value) {
        return next.apply(Specs.comparison(path, ComparisonOperator.NE, Literal.of(value)));
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
    public B in(Collection<? extends String> values) {
        return next.apply(Specs.membership(path, values, false));
    }

    @Override
    @SafeVarargs
    public final B in(String... values) {
        return next.apply(Specs.membership(path, Arrays.asList(values), false));
    }

    @Override
    public B notIn(Collection<? extends String> values) {
        return next.apply(Specs.membership(path, values, true));
    }

    @Override
    @SafeVarargs
    public final B notIn(String... values) {
        return next.apply(Specs.membership(path, Arrays.asList(values), true));
    }

    @Override
    public B inIfNotNull(Collection<? extends String> values) {
        return values == null ? skip.get() : in(values);
    }

    @Override
    public B contains(String value) {
        return next.apply(Specs.contains(path, value));
    }

    @Override
    public B containsIfNotEmpty(String value) {
        return isEmpty(value) ? skip.get() : contains(value);
    }

    @Override
    public B containsIfNotNull(String value) {
        return value == null ? skip.get() : contains(value);
    }

    @Override
    public B like(String pattern) {
        return next.apply(Specs.like(path, pattern));
    }

    @Override
    public B likeIfNotNull(String pattern) {
        return pattern == null ? skip.get() : like(pattern);
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
