package io.github.nextentity.api;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/// 表达式值的组装工具：**只拼值，不求值**。
///
/// 把 `Operation` 翻译成 `Spec` 节点是后端（`core.builder`）的职责；这里除 `*IfNotNull`
/// 的 null 短路外没有任何判断逻辑。
@SuppressWarnings("PMD.CouplingBetweenObjects") // 同一个文件承载了所有表达式值适配类
final class Conditions {

    private static final AlwaysTrue<?> ALWAYS_TRUE = new AlwaysTrue<>();

    /// 聚合链错误的统一消息（同一字面量出现 4 次会被 PMD 判重复）。
    private static final String AGGREGATE_CHAIN = "聚合结果不能再聚合";

    private Conditions() {}

    /// 条件被短路时的返回值：**不实现 `Op`**，后端遇到它即视为"无附加条件"。
    ///
    /// @param <T> 根实体类型
    /// @return 恒真占位条件
    @SuppressWarnings("unchecked")
    static <T> Predicate<T> alwaysTrue() {
        return (Predicate<T>) ALWAYS_TRUE;
    }

    static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    /// 独立路径（`Path.of(...)` / `EntityPath.of(...)`）与 `where(...)` 之后的算子共用同一套组装逻辑。
    ///
    /// @param <T> 根实体类型
    abstract static class AbstractOperators<T> implements Serializable {

        private static final long serialVersionUID = 1L;

        private final List<PathRef<?, ?>> refs;
        private final Predicate<T> parent;
        private final Operation combine;
        private final boolean suppressed;

        AbstractOperators(List<PathRef<?, ?>> refs, Predicate<T> parent, Operation combine, boolean suppressed) {
            this.refs = new ArrayList<>(refs);
            this.parent = parent;
            this.combine = combine;
            this.suppressed = suppressed;
        }

        public final List<PathRef<?, ?>> refs() {
            return Collections.unmodifiableList(refs);
        }

        final Predicate<T> comparison(Operation operation, Object value) {
            return merge(new Leaf<>(operation, refs, Collections.singletonList(value)));
        }

        final Predicate<T> range(Operation operation, Object from, Object to) {
            return merge(new Leaf<>(operation, refs, Arrays.asList(from, to)));
        }

        final Predicate<T> membership(Operation operation, List<?> values) {
            return merge(new Leaf<>(operation, refs, Collections.singletonList(values)));
        }

        final Predicate<T> nullCheck(Operation operation) {
            return merge(new Leaf<>(operation, refs, List.of()));
        }

        final Predicate<T> stringOp(Operation operation, String pattern) {
            return merge(new Leaf<>(operation, refs, Collections.singletonList(pattern)));
        }

        private Predicate<T> merge(Predicate<T> next) {
            if (suppressed) {
                return alwaysTrue();
            }
            return parent == null ? next : new Leaf<>(combine, List.of(), List.of(parent, next));
        }
    }

    /// 叶子条件或逻辑组合；`AND`/`OR` 的子条件放在 `arguments()` 里。
    ///
    /// @param <T> 根实体类型
    static final class Leaf<T> implements Predicate<T>, Op {

        private final Operation operation;
        private final List<PathRef<?, ?>> refs;
        private final List<Object> arguments;

        Leaf(Operation operation, List<PathRef<?, ?>> refs, List<Object> arguments) {
            this.operation = operation;
            this.refs = Collections.unmodifiableList(new ArrayList<>(refs));
            this.arguments = Collections.unmodifiableList(new ArrayList<>(arguments));
        }

        @Override
        public Operation operation() {
            return operation;
        }

        @Override
        public List<PathRef<?, ?>> refs() {
            return refs;
        }

        @Override
        public List<Object> arguments() {
            return arguments;
        }

        @Override
        public Predicate<T> and(Expression<T, Boolean> other) {
            return new Leaf<>(Operation.AND, List.of(), Arrays.asList(this, other));
        }

        @Override
        public Predicate<T> or(Expression<T, Boolean> other) {
            return new Leaf<>(Operation.OR, List.of(), Arrays.asList(this, other));
        }

        @Override
        public <N> PathOperator<T, N, Predicate<T>> or(PathRef<T, N> ref) {
            return new PathOperatorValue<>(ref, this, Operation.OR, false);
        }
    }

    /// 被短路的条件：不实现 `Op`，逻辑上恒真。
    ///
    /// @param <T> 根实体类型
    static final class AlwaysTrue<T> implements Predicate<T> {

        @Override
        public Predicate<T> and(Expression<T, Boolean> other) {
            return new Leaf<>(Operation.AND, List.of(), Arrays.asList(this, other));
        }

        @Override
        public Predicate<T> or(Expression<T, Boolean> other) {
            return this;
        }

        @Override
        public <N> PathOperator<T, N, Predicate<T>> or(PathRef<T, N> ref) {
            return new PathOperatorValue<>(ref, null, Operation.OR, true);
        }
    }

    @SuppressWarnings("PMD.TooManyMethods") // 方法数由 PathOperator 接口面决定，不是上帝类
    static final class PathOperatorValue<T, N> extends AbstractOperators<T>
            implements PathOperator<T, N, Predicate<T>> {

        private static final long serialVersionUID = 1L;

        PathOperatorValue(PathRef<T, N> ref, Predicate<T> parent, Operation combine, boolean suppressed) {
            super(List.of(ref), parent, combine, suppressed);
        }

        @Override
        public Predicate<T> eq(N value) {
            return comparison(Operation.EQ, value);
        }

        @Override
        public Predicate<T> eqIfNotNull(N value) {
            return value == null ? alwaysTrue() : comparison(Operation.EQ, value);
        }

        @Override
        public Predicate<T> ne(N value) {
            return comparison(Operation.NE, value);
        }

        @Override
        public Predicate<T> ge(N value) {
            return comparison(Operation.GE, value);
        }

        @Override
        public Predicate<T> gt(N value) {
            return comparison(Operation.GT, value);
        }

        @Override
        public Predicate<T> le(N value) {
            return comparison(Operation.LE, value);
        }

        @Override
        public Predicate<T> lt(N value) {
            return comparison(Operation.LT, value);
        }

        @Override
        public Predicate<T> geIfNotNull(N value) {
            return value == null ? alwaysTrue() : comparison(Operation.GE, value);
        }

        @Override
        public Predicate<T> gtIfNotNull(N value) {
            return value == null ? alwaysTrue() : comparison(Operation.GT, value);
        }

        @Override
        public Predicate<T> leIfNotNull(N value) {
            return value == null ? alwaysTrue() : comparison(Operation.LE, value);
        }

        @Override
        public Predicate<T> ltIfNotNull(N value) {
            return value == null ? alwaysTrue() : comparison(Operation.LT, value);
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
        public Predicate<T> in(Collection<? extends N> values) {
            return membership(Operation.IN, new ArrayList<>(values));
        }

        @Override
        public Predicate<T> in(N... values) {
            return membership(Operation.IN, Arrays.asList(values));
        }

        @Override
        public Predicate<T> notIn(Collection<? extends N> values) {
            return membership(Operation.NOT_IN, new ArrayList<>(values));
        }

        @Override
        public Predicate<T> notIn(N... values) {
            return membership(Operation.NOT_IN, Arrays.asList(values));
        }

        @Override
        public Predicate<T> inIfNotNull(Collection<? extends N> values) {
            return values == null ? alwaysTrue() : membership(Operation.IN, new ArrayList<>(values));
        }

        @Override
        public Predicate<T> between(N from, N to) {
            return range(Operation.BETWEEN, from, to);
        }
    }

    @SuppressWarnings("PMD.TooManyMethods") // 方法数由 StringOperator 接口面决定，不是上帝类
    static final class StringOperatorValue<T> extends AbstractOperators<T> implements StringOperator<T, Predicate<T>> {

        private static final long serialVersionUID = 1L;

        StringOperatorValue(PathRef.StringRef<T> ref, Predicate<T> parent, Operation combine) {
            super(List.of(ref), parent, combine, false);
        }

        @Override
        public Predicate<T> eq(String value) {
            return comparison(Operation.EQ, value);
        }

        @Override
        public Predicate<T> eqIfNotNull(String value) {
            return value == null ? alwaysTrue() : comparison(Operation.EQ, value);
        }

        @Override
        public Predicate<T> ne(String value) {
            return comparison(Operation.NE, value);
        }

        @Override
        public Predicate<T> eqIfNotEmpty(String value) {
            return isEmpty(value) ? alwaysTrue() : comparison(Operation.EQ, value);
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
        public Predicate<T> in(Collection<? extends String> values) {
            return membership(Operation.IN, new ArrayList<>(values));
        }

        @Override
        public Predicate<T> in(String... values) {
            return membership(Operation.IN, Arrays.asList(values));
        }

        @Override
        public Predicate<T> notIn(Collection<? extends String> values) {
            return membership(Operation.NOT_IN, new ArrayList<>(values));
        }

        @Override
        public Predicate<T> notIn(String... values) {
            return membership(Operation.NOT_IN, Arrays.asList(values));
        }

        @Override
        public Predicate<T> inIfNotNull(Collection<? extends String> values) {
            return values == null ? alwaysTrue() : membership(Operation.IN, new ArrayList<>(values));
        }

        @Override
        public Predicate<T> contains(String value) {
            return stringOp(Operation.CONTAINS, value);
        }

        @Override
        public Predicate<T> containsIfNotEmpty(String value) {
            return isEmpty(value) ? alwaysTrue() : stringOp(Operation.CONTAINS, value);
        }

        @Override
        public Predicate<T> containsIfNotNull(String value) {
            return value == null ? alwaysTrue() : stringOp(Operation.CONTAINS, value);
        }

        @Override
        public Predicate<T> likeIfNotNull(String pattern) {
            return pattern == null ? alwaysTrue() : stringOp(Operation.LIKE, pattern);
        }
    }

    static <T, U> Expression<T, U> aggregate(Operation operation, List<PathRef<?, ?>> refs) {
        return new AggregateValue<>(operation, refs);
    }

    static <T> NumberExpression<T, Long> countLike(Operation operation, List<PathRef<?, ?>> refs) {
        return new AggregateNumber<>(operation, refs);
    }

    static <T, U extends Number> NumberExpression<T, U> numericAggregate(
            Operation operation, List<PathRef<?, ?>> refs) {
        return new AggregateNumber<>(operation, refs);
    }

    abstract static class AbstractAggregate<T, U> implements Op {

        private final Operation operation;
        private final List<PathRef<?, ?>> refs;

        AbstractAggregate(Operation operation, List<PathRef<?, ?>> refs) {
            this.operation = operation;
            this.refs = Collections.unmodifiableList(new ArrayList<>(refs));
        }

        @Override
        public Operation operation() {
            return operation;
        }

        @Override
        public List<PathRef<?, ?>> refs() {
            return refs;
        }

        @Override
        public List<Object> arguments() {
            return List.of();
        }
    }

    static final class AggregateValue<T, U> extends AbstractAggregate<T, U> implements Expression<T, U> {

        AggregateValue(Operation operation, List<PathRef<?, ?>> refs) {
            super(operation, refs);
        }
    }

    static final class AggregateNumber<T, U extends Number> extends AbstractAggregate<T, U>
            implements NumberExpression<T, U> {

        AggregateNumber(Operation operation, List<PathRef<?, ?>> refs) {
            super(operation, refs);
        }

        @Override
        public NumberExpression<T, U> sum() {
            throw new UnsupportedOperationException(AGGREGATE_CHAIN);
        }

        @Override
        public NumberExpression<T, Long> count() {
            throw new UnsupportedOperationException(AGGREGATE_CHAIN);
        }

        @Override
        public NumberExpression<T, Long> countDistinct() {
            throw new UnsupportedOperationException(AGGREGATE_CHAIN);
        }

        @Override
        public NumberExpression<T, U> max() {
            throw new UnsupportedOperationException(AGGREGATE_CHAIN);
        }
    }
}
