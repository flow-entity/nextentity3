package io.github.nextentity.core.builder;

import io.github.nextentity.api.StringOperator;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.Logical;
import io.github.nextentity.core.spec.Operand;
import io.github.nextentity.core.spec.Spec;
import io.github.nextentity.core.spec.StringFunction;
import io.github.nextentity.core.spec.StringFunctionOperand;
import io.github.nextentity.core.spec.SubstringOperand;
import java.util.Arrays;
import java.util.Collection;
import java.util.function.Function;
import java.util.function.Supplier;

/// 查询链上的字符串算子：`query.where(X::getName)` 之后的 `contains/...`。
///
/// `CONTAINS` 系列在这里展开成 `%value%` 的 LIKE 模式（2.2.3 `ExpressionBuilder:520-522`），
/// 通配符只在这一处拼；`like` / `likeIfNotNull` 原样透传调用方自带的模式。
///
/// 函数算子（`lower/upper/trim/substring`）不立即产生条件，而是把"主语"换成函数节点，
/// 再返回一个新的算子实例；谓词只在链末的 `like/contains/eq/...` 处产生。
/// 因此 `lower().contains(v)` 的条件主语是 `LOWER(name)`，不是 `name`。
///
/// @param <T> 根实体类型
/// @param <B> 查询步骤类型
@SuppressWarnings("PMD.TooManyMethods") // 方法数由 api.StringOperator 继承的接口面决定
final class StringStepOperator<T, B> implements StringOperator<T, B> {

    private final AttributePath path;
    private final Operand subject;
    private final Function<Spec, B> next;
    private final Supplier<B> skip;

    StringStepOperator(AttributePath path, Function<Spec, B> next, Supplier<B> skip) {
        this(path, path, next, skip);
    }

    private StringStepOperator(AttributePath path, Operand subject, Function<Spec, B> next, Supplier<B> skip) {
        this.path = path;
        this.subject = subject;
        this.next = next;
        this.skip = skip;
    }

    @Override
    public B eq(String value) {
        return next.apply(Specs.comparison(subject, ComparisonOperator.EQ, Literal.of(value)));
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
        return next.apply(Specs.comparison(subject, ComparisonOperator.NE, Literal.of(value)));
    }

    @Override
    public B isNull() {
        return next.apply(Specs.nullCheck(rawPath(IS_NULL), false));
    }

    @Override
    public B isNotNull() {
        return next.apply(Specs.nullCheck(rawPath(IS_NOT_NULL), true));
    }

    @Override
    public B in(Collection<? extends String> values) {
        return next.apply(Specs.membership(rawPath(IN), values, false));
    }

    @Override
    public B in(String... values) {
        return next.apply(Specs.membership(rawPath(IN), Arrays.asList(values), false));
    }

    @Override
    public B notIn(Collection<? extends String> values) {
        return next.apply(Specs.membership(rawPath(NOT_IN), values, true));
    }

    @Override
    public B notIn(String... values) {
        return next.apply(Specs.membership(rawPath(NOT_IN), Arrays.asList(values), true));
    }

    @Override
    public B inIfNotNull(Collection<? extends String> values) {
        return values == null ? skip.get() : in(values);
    }

    @Override
    public B contains(String value) {
        return next.apply(Specs.contains(subject, value));
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
        return next.apply(Specs.like(subject, pattern));
    }

    @Override
    public B likeIfNotNull(String pattern) {
        return pattern == null ? skip.get() : like(pattern);
    }

    @Override
    public B notLike(String pattern) {
        return next.apply(Logical.not(Specs.like(subject, pattern)));
    }

    @Override
    public B notLikeIfNotNull(String pattern) {
        return pattern == null ? skip.get() : notLike(pattern);
    }

    @Override
    public StringOperator<T, B> lower() {
        return function(StringFunction.LOWER);
    }

    @Override
    public StringOperator<T, B> upper() {
        return function(StringFunction.UPPER);
    }

    @Override
    public StringOperator<T, B> trim() {
        return function(StringFunction.TRIM);
    }

    @Override
    public StringOperator<T, B> substring(int offset) {
        // length 为 null = 截到末尾：渲染成语义上的「无长度」形态，不下发上界数字
        return new StringStepOperator<>(path, new SubstringOperand(subject, offset, null), next, skip);
    }

    @Override
    public StringOperator<T, B> substring(int offset, int length) {
        return new StringStepOperator<>(path, new SubstringOperand(subject, offset, length), next, skip);
    }

    @Override
    public NumberFunctionOperator<T, B> length() {
        return new NumberFunctionOperator<>(new StringFunctionOperand(StringFunction.LENGTH, subject), next, skip);
    }

    private StringOperator<T, B> function(StringFunction function) {
        return new StringStepOperator<>(path, new StringFunctionOperand(function, subject), next, skip);
    }

    /// `IS NULL` / `IN` 的主语只能是实体属性：函数结果上没有"列为 null"或"列在集合里"的语义，
    /// 静默降级成对属性判断会答错，因此显式拒绝。
    private AttributePath rawPath(String operation) {
        if (subject instanceof AttributePath attributePath) {
            return attributePath;
        }
        throw new UnsupportedOperationException(
                "字符串函数结果不支持 " + operation + "：只支持与值比较（eq/ne/like/contains/大小比较），主语是 " + subject);
    }

    /// 运算名常量：同一条消息在 `Collection` 与 varargs 两个重载里各出现一次，抽出来避免字面量重复。
    private static final String IS_NULL = "isNull()";
    private static final String IS_NOT_NULL = "isNotNull()";
    private static final String IN = "in(...)";
    private static final String NOT_IN = "notIn(...)";

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
