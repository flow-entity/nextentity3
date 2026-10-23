package io.github.nextentity.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/// `Path.of(...)` / `EntityPath.of(...)` 的纯值实现：只承载方法引用链，语义由后端解释。
///
/// 放在 `api` 的理由：静态工厂必须能返回**可用**对象，而 `api` 不允许依赖 `core`；
/// 这些类不依赖任何三方类型，也不做求值。
@SuppressWarnings("PMD.CouplingBetweenObjects") // 同一个文件承载 Path 家族的全部值实现（Root/Nested/字符串/数值），拆开反而多出跨文件可见性
final class PathValues {

    private PathValues() {}

    static List<PathRef<?, ?>> append(List<PathRef<?, ?>> refs, PathRef<?, ?> next) {
        List<PathRef<?, ?>> result = new ArrayList<>(refs);
        result.add(next);
        return result;
    }

    @SuppressWarnings("PMD.TooManyMethods") // 方法数由 Path 接口面决定，不是上帝类
    abstract static class Value<T, U> extends Conditions.AbstractOperators<T> implements Path<T, U> {

        Value(List<PathRef<?, ?>> refs) {
            super(refs, null, null, false);
        }

        @Override
        public AggregateValue<T, Long> count() {
            return Conditions.aggregate(Operation.COUNT, refs());
        }

        @Override
        public AggregateValue<T, Long> countDistinct() {
            return Conditions.aggregate(Operation.COUNT_DISTINCT, refs());
        }

        @Override
        public AggregateValue<T, U> max() {
            return Conditions.aggregate(Operation.MAX, refs());
        }

        @Override
        public AggregateValue<T, U> min() {
            return Conditions.aggregate(Operation.MIN, refs());
        }

        @Override
        public Predicate<T> eq(U value) {
            return comparison(Operation.EQ, value);
        }

        @Override
        public Predicate<T> eq(Expression<T, U> other) {
            return comparison(Operation.EQ, other);
        }

        @Override
        public Predicate<T> eqIfNotNull(U value) {
            return value == null ? Conditions.alwaysTrue() : comparison(Operation.EQ, value);
        }

        @Override
        public Predicate<T> ne(U value) {
            return comparison(Operation.NE, value);
        }

        @Override
        public Predicate<T> ne(Expression<T, U> other) {
            return comparison(Operation.NE, other);
        }

        @Override
        public Predicate<T> ge(U value) {
            return comparison(Operation.GE, value);
        }

        @Override
        public Predicate<T> ge(Expression<T, U> other) {
            return comparison(Operation.GE, other);
        }

        @Override
        public Predicate<T> gt(U value) {
            return comparison(Operation.GT, value);
        }

        @Override
        public Predicate<T> gt(Expression<T, U> other) {
            return comparison(Operation.GT, other);
        }

        @Override
        public Predicate<T> le(U value) {
            return comparison(Operation.LE, value);
        }

        @Override
        public Predicate<T> le(Expression<T, U> other) {
            return comparison(Operation.LE, other);
        }

        @Override
        public Predicate<T> lt(U value) {
            return comparison(Operation.LT, value);
        }

        @Override
        public Predicate<T> lt(Expression<T, U> other) {
            return comparison(Operation.LT, other);
        }

        @Override
        public Predicate<T> geIfNotNull(U value) {
            return value == null ? Conditions.alwaysTrue() : comparison(Operation.GE, value);
        }

        @Override
        public Predicate<T> gtIfNotNull(U value) {
            return value == null ? Conditions.alwaysTrue() : comparison(Operation.GT, value);
        }

        @Override
        public Predicate<T> leIfNotNull(U value) {
            return value == null ? Conditions.alwaysTrue() : comparison(Operation.LE, value);
        }

        @Override
        public Predicate<T> ltIfNotNull(U value) {
            return value == null ? Conditions.alwaysTrue() : comparison(Operation.LT, value);
        }

        @Override
        public Predicate<T> isNull() {
            return nullCheck(Operation.IS_NULL);
        }

        @Override
        public Predicate<T> isNotNull() {
            return nullCheck(Operation.IS_NOT_NULL);
        }

        @Override
        public Predicate<T> in(Collection<? extends U> values) {
            return membership(Operation.IN, new ArrayList<>(values));
        }

        @Override
        public Predicate<T> in(U... values) {
            return membership(Operation.IN, List.of(values));
        }

        @Override
        public Predicate<T> notIn(Collection<? extends U> values) {
            return membership(Operation.NOT_IN, new ArrayList<>(values));
        }

        @Override
        public Predicate<T> notIn(U... values) {
            return membership(Operation.NOT_IN, List.of(values));
        }

        @Override
        public Predicate<T> inIfNotNull(Collection<? extends U> values) {
            return values == null ? Conditions.alwaysTrue() : membership(Operation.IN, new ArrayList<>(values));
        }

        @Override
        public Predicate<T> between(U from, U to) {
            return range(Operation.BETWEEN, from, to);
        }

        @Override
        public Predicate<T> between(Expression<T, U> from, Expression<T, U> to) {
            return range(Operation.BETWEEN, from, to);
        }

        @Override
        public Predicate<T> notBetween(U from, U to) {
            return between(from, to).not();
        }
    }

    static final class Root<T, U> extends Value<T, U> {

        Root(PathRef<T, U> ref) {
            super(Collections.singletonList(ref));
        }
    }

    @SuppressWarnings("PMD.TooManyMethods") // 方法数由 NumberPath 接口面决定，不是上帝类
    static final class NumberRoot<T, U extends Number> extends Value<T, U> implements NumberPath<T, U> {

        NumberRoot(PathRef.NumberRef<T, U> ref) {
            super(Collections.singletonList(ref));
        }

        @Override
        public AggregateValue<T, U> sum() {
            return Conditions.aggregate(Operation.SUM, refs());
        }

        @Override
        public AggregateValue<T, Double> avg() {
            return Conditions.aggregate(Operation.AVG, refs());
        }

        @Override
        public NumberValue<T, U> add(U addend) {
            return Conditions.arithmetic(Operation.ADD, refs(), addend, null);
        }

        @Override
        public NumberValue<T, U> sub(U subtrahend) {
            return Conditions.arithmetic(Operation.SUB, refs(), subtrahend, null);
        }

        @Override
        public NumberValue<T, U> mul(U multiplier) {
            return Conditions.arithmetic(Operation.MUL, refs(), multiplier, null);
        }

        @Override
        public NumberValue<T, U> div(U divisor) {
            return Conditions.arithmetic(Operation.DIV, refs(), divisor, null);
        }

        @Override
        public NumberValue<T, U> mod(U divisor) {
            return Conditions.arithmetic(Operation.MOD, refs(), divisor, null);
        }
    }

    abstract static class EntityValue<T, U> extends Value<T, U> implements EntityPath<T, U> {

        EntityValue(List<PathRef<?, ?>> refs) {
            super(refs);
        }

        @Override
        public <R> EntityPath<T, R> get(PathRef<U, R> ref) {
            return new Nested<>(append(refs(), ref));
        }

        @Override
        public StringPath<T> get(PathRef.StringRef<U> ref) {
            return new NestedString<>(append(refs(), ref));
        }
    }

    static final class EntityRoot<T, U> extends EntityValue<T, U> {

        EntityRoot(PathRef<T, U> ref) {
            super(Collections.singletonList(ref));
        }
    }

    static final class Nested<T, U> extends EntityValue<T, U> {

        Nested(List<PathRef<?, ?>> refs) {
            super(refs);
        }
    }

    /// 字符串路径的公共实现：`Path<T,String>` + 一组字符串算子。
    ///
    /// @param <T> 根实体类型
    abstract static class StringValue<T> extends Value<T, String> implements StringPath<T> {

        StringValue(List<PathRef<?, ?>> refs) {
            super(refs);
        }

        @Override
        public Predicate<T> contains(String value) {
            return stringOp(Operation.CONTAINS, value);
        }

        @Override
        public Predicate<T> containsIfNotEmpty(String value) {
            return value == null || value.isEmpty() ? Conditions.alwaysTrue() : stringOp(Operation.CONTAINS, value);
        }

        @Override
        public Predicate<T> containsIfNotNull(String value) {
            return value == null ? Conditions.alwaysTrue() : stringOp(Operation.CONTAINS, value);
        }

        @Override
        public Predicate<T> like(String pattern) {
            return stringOp(Operation.LIKE, pattern);
        }

        @Override
        public Predicate<T> likeIfNotNull(String pattern) {
            return pattern == null ? Conditions.alwaysTrue() : stringOp(Operation.LIKE, pattern);
        }

        @Override
        public Predicate<T> eqIfNotEmpty(String value) {
            return value == null || value.isEmpty() ? Conditions.alwaysTrue() : comparison(Operation.EQ, value);
        }
    }

    static final class StringRoot<T> extends StringValue<T> {

        StringRoot(PathRef.StringRef<T> ref) {
            super(Collections.singletonList(ref));
        }
    }

    static final class NestedString<T> extends StringValue<T> {

        NestedString(List<PathRef<?, ?>> refs) {
            super(refs);
        }
    }
}
