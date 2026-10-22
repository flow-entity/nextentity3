package io.github.nextentity.api;

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

    private Conditions() {}

    /// 条件被短路时的返回值：**不实现 `Op`**，后端遇到它即视为"无附加条件"。
    ///
    /// @param <T> 根实体类型
    /// @return 单例占位谓词：所有短路条件共用同一个实例，可用 `==` 比较
    @SuppressWarnings("unchecked")
    static <T> Predicate<T> alwaysTrue() {
        return (Predicate<T>) ALWAYS_TRUE;
    }

    /// 独立路径（`Path.of(...)` / `EntityPath.of(...)`）与 `where(...)` 之后的算子共用同一套组装逻辑。
    ///
    /// @param <T> 根实体类型
    abstract static class AbstractOperators<T> {

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

    static <T, U> Expression<T, U> aggregate(Operation operation, List<PathRef<?, ?>> refs) {
        return new AggregateValue<>(operation, refs);
    }

    static <T> Expression<T, Long> countLike(Operation operation, List<PathRef<?, ?>> refs) {
        return new AggregateValue<>(operation, refs);
    }

    static <T, U extends Number> Expression<T, U> numericAggregate(Operation operation, List<PathRef<?, ?>> refs) {
        return new AggregateValue<>(operation, refs);
    }

    abstract static non-sealed class AbstractAggregate<T, U> implements Op {

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
}
