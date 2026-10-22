package io.github.nextentity.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/// `Path.of(...)` / `EntityPath.of(...)` 的纯值实现：只承载方法引用链，语义由后端解释。
///
/// 放在 `api` 的理由：静态工厂必须能返回**可用**对象，而 `api` 不允许依赖 `core`；
/// 这些类不依赖任何三方类型，也不做求值。
final class PathValues {

    private PathValues() {}

    static List<PathRef<?, ?>> append(List<PathRef<?, ?>> refs, PathRef<?, ?> next) {
        List<PathRef<?, ?>> result = new ArrayList<>(refs);
        result.add(next);
        return result;
    }

    @SuppressWarnings("PMD.TooManyMethods") // 方法数由 Path 接口面决定，不是上帝类
    abstract static class Value<T, U> extends Conditions.AbstractOperators<T> implements Path<T, U> {

        private static final long serialVersionUID = 1L;

        Value(List<PathRef<?, ?>> refs) {
            super(refs, null, null, false);
        }

        @Override
        @SuppressWarnings("unchecked")
        public U apply(T entity) {
            Object current = entity;
            for (PathRef<?, ?> ref : refs()) {
                current = ((PathRef<Object, Object>) ref).apply(current);
            }
            return (U) current;
        }

        @Override
        public NumberExpression<T, Long> count() {
            return Conditions.countLike(Operation.COUNT, refs());
        }

        @Override
        public NumberExpression<T, Long> countDistinct() {
            return Conditions.countLike(Operation.COUNT_DISTINCT, refs());
        }

        @Override
        public Expression<T, U> max() {
            return Conditions.aggregate(Operation.MAX, refs());
        }

        @Override
        public Predicate<T> eq(U value) {
            return comparison(Operation.EQ, value);
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
        public Predicate<T> ge(U value) {
            return comparison(Operation.GE, value);
        }

        @Override
        public Predicate<T> gt(U value) {
            return comparison(Operation.GT, value);
        }

        @Override
        public Predicate<T> le(U value) {
            return comparison(Operation.LE, value);
        }

        @Override
        public Predicate<T> lt(U value) {
            return comparison(Operation.LT, value);
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
    }

    static final class Root<T, U> extends Value<T, U> {
        private static final long serialVersionUID = 1L;

        Root(PathRef<T, U> ref) {
            super(Collections.singletonList(ref));
        }
    }

    static final class NumberRoot<T, U extends Number> extends Value<T, U> implements NumberPath<T, U> {
        private static final long serialVersionUID = 1L;

        NumberRoot(PathRef.NumberRef<T, U> ref) {
            super(Collections.singletonList(ref));
        }

        @Override
        public NumberExpression<T, U> sum() {
            return Conditions.numericAggregate(Operation.SUM, refs());
        }

        @Override
        public NumberExpression<T, U> max() {
            return Conditions.numericAggregate(Operation.MAX, refs());
        }
    }

    static final class StringRoot<T> extends Value<T, String> implements StringPath<T> {
        private static final long serialVersionUID = 1L;

        StringRoot(PathRef.StringRef<T> ref) {
            super(Collections.singletonList(ref));
        }
    }

    abstract static class EntityValue<T, U> extends Value<T, U> implements EntityPath<T, U> {
        private static final long serialVersionUID = 1L;

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
        private static final long serialVersionUID = 1L;

        EntityRoot(PathRef<T, U> ref) {
            super(Collections.singletonList(ref));
        }
    }

    static final class Nested<T, U> extends EntityValue<T, U> {
        private static final long serialVersionUID = 1L;

        Nested(List<PathRef<?, ?>> refs) {
            super(refs);
        }
    }

    static final class NestedString<T> extends Value<T, String> implements StringPath<T> {
        private static final long serialVersionUID = 1L;

        NestedString(List<PathRef<?, ?>> refs) {
            super(refs);
        }
    }
}
