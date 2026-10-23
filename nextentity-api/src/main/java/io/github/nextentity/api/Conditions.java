package io.github.nextentity.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/// 表达式值的组装工具：**只拼值，不求值**。
///
/// 把 `Operation` 翻译成 `Spec` 节点是后端（`core.builder`）的职责；这里除了
/// `*IfNotNull` / `*IfNotEmpty` 的短路判断，没有任何求值逻辑。
@SuppressWarnings("PMD.CouplingBetweenObjects") // 同一个文件承载了所有表达式值适配类
final class Conditions {

    private static final AlwaysTrue<?> ALWAYS_TRUE = new AlwaysTrue<>();

    /// `and` / `or` 集合参数的参数名，用于空值与元素校验消息（同名字面量只留一处）。
    private static final String OTHERS = "others";

    private Conditions() {}

    /// 条件被短路时的返回值：**不实现 `Op`**，后端遇到它即视为"无附加条件"。
    ///
    /// @param <T> 根实体类型
    /// @return 单例占位谓词：所有短路条件共用同一个实例，可用 `==` 比较
    @SuppressWarnings("unchecked")
    static <T> Predicate<T> alwaysTrue() {
        return (Predicate<T>) ALWAYS_TRUE;
    }

    /// 独立路径（`Path.of(...)` / `EntityPath.of(...)`）、`where(...)` 之后的算子、
    /// 聚合值与算术值共用的组装逻辑。
    ///
    /// @param <T> 根实体类型
    abstract static class AbstractOperators<T> {

        private final List<PathRef<?, ?>> refs;
        private final Predicate<T> parent;
        private final Operation combine;
        private final boolean suppressed;

        AbstractOperators(List<PathRef<?, ?>> refs, Predicate<T> parent, Operation combine, boolean suppressed) {
            this.refs = List.copyOf(refs);
            this.parent = parent;
            this.combine = combine;
            this.suppressed = suppressed;
        }

        public final List<PathRef<?, ?>> refs() {
            return refs;
        }

        final Predicate<T> comparison(Operation operation, Object value) {
            return comparison(operation, value, null);
        }

        final Predicate<T> comparison(Operation operation, Object value, Op subject) {
            return merge(new Leaf<>(operation, refs, Collections.singletonList(value), subject));
        }

        final Predicate<T> range(Operation operation, Object from, Object to) {
            return range(operation, from, to, null);
        }

        final Predicate<T> range(Operation operation, Object from, Object to, Op subject) {
            return merge(new Leaf<>(operation, refs, List.of(from, to), subject));
        }

        final Predicate<T> membership(Operation operation, List<?> values) {
            return merge(new Leaf<>(operation, refs, Collections.singletonList(values), null));
        }

        final Predicate<T> nullCheck(Operation operation) {
            return merge(new Leaf<>(operation, refs, List.of(), null));
        }

        final Predicate<T> stringOp(Operation operation, String pattern) {
            return merge(new Leaf<>(operation, refs, Collections.singletonList(pattern), null));
        }

        private Predicate<T> merge(Predicate<T> next) {
            if (suppressed) {
                return alwaysTrue();
            }
            return parent == null ? next : new Leaf<>(combine, List.of(), List.of(parent, next), null);
        }
    }

    /// 叶子条件或逻辑组合；`AND`/`OR`/`NOT` 的子条件放在 `arguments()` 里。
    ///
    /// @param <T> 根实体类型
    static final class Leaf<T> implements Predicate<T>, Op {

        private final Operation operation;
        private final List<PathRef<?, ?>> refs;
        private final List<Object> arguments;
        private final Op subject;

        Leaf(Operation operation, List<PathRef<?, ?>> refs, List<Object> arguments) {
            this(operation, refs, arguments, null);
        }

        Leaf(Operation operation, List<PathRef<?, ?>> refs, List<Object> arguments, Op subject) {
            this.operation = operation;
            this.refs = List.copyOf(refs);
            this.arguments = List.copyOf(arguments);
            this.subject = subject;
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
        public Op subject() {
            return subject;
        }

        @Override
        public Predicate<T> and(Expression<T, Boolean> other) {
            return new Leaf<>(Operation.AND, List.of(), List.of(this, other), null);
        }

        @Override
        public Predicate<T> or(Expression<T, Boolean> other) {
            return new Leaf<>(Operation.OR, List.of(), List.of(this, other), null);
        }

        @Override
        public Predicate<T> and(Collection<? extends Expression<T, Boolean>> others) {
            Objects.requireNonNull(others, OTHERS);
            return others.isEmpty() ? this : new Leaf<>(Operation.AND, List.of(), operands(this, others), null);
        }

        @Override
        public Predicate<T> or(Collection<? extends Expression<T, Boolean>> others) {
            Objects.requireNonNull(others, OTHERS);
            return others.isEmpty() ? this : new Leaf<>(Operation.OR, List.of(), operands(this, others), null);
        }

        @Override
        public Predicate<T> not() {
            return new Leaf<>(Operation.NOT, List.of(), List.of(this), null);
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
            return new Leaf<>(Operation.AND, List.of(), List.of(this, other), null);
        }

        @Override
        public Predicate<T> or(Expression<T, Boolean> other) {
            return this;
        }

        @Override
        public Predicate<T> and(Collection<? extends Expression<T, Boolean>> others) {
            Objects.requireNonNull(others, OTHERS);
            // TRUE ∧ x = x：恒真占位不必保留
            return others.isEmpty() ? this : new Leaf<>(Operation.AND, List.of(), operands(others), null);
        }

        @Override
        public Predicate<T> or(Collection<? extends Expression<T, Boolean>> others) {
            // TRUE ∨ x = TRUE：无条件返回自身，空集合也一样
            Objects.requireNonNull(others, OTHERS);
            return this;
        }

        @Override
        public Predicate<T> not() {
            return new Leaf<>(Operation.NOT, List.of(), List.of(this), null);
        }

        @Override
        public <N> PathOperator<T, N, Predicate<T>> or(PathRef<T, N> ref) {
            return new PathOperatorValue<>(ref, null, Operation.OR, true);
        }
    }

    /// 拼 n 元 `and/or` 的操作数：`head` + `others`，元素为 `null` 时拒绝（不做静默丢弃）。
    ///
    /// 这里**不做语义决策**——空集合的语义由调用方决定（`a ∧ 空 = a`、`a ∨ 空 = a`），
    /// 因此调用方必须先判空再决定要不要拼。
    private static <T> List<Object> operands(
            Expression<T, Boolean> head, Collection<? extends Expression<T, Boolean>> others) {
        List<Object> operands = new ArrayList<>(others.size() + 1);
        operands.add(head);
        operands.addAll(checked(others));
        return operands;
    }

    /// 只校验并复制元素，不加接收者（`TRUE ∧ x = x` 时用）。
    private static List<Object> operands(Collection<? extends Expression<?, Boolean>> others) {
        return checked(others);
    }

    private static List<Object> checked(Collection<? extends Expression<?, Boolean>> others) {
        List<Object> operands = new ArrayList<>(others.size());
        for (Expression<?, Boolean> other : others) {
            operands.add(Objects.requireNonNull(other, "others 元素不能为 null"));
        }
        return operands;
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
        public Predicate<T> eq(Expression<T, N> other) {
            return comparison(Operation.EQ, other);
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
        public Predicate<T> ne(Expression<T, N> other) {
            return comparison(Operation.NE, other);
        }

        @Override
        public Predicate<T> ge(N value) {
            return comparison(Operation.GE, value);
        }

        @Override
        public Predicate<T> ge(Expression<T, N> other) {
            return comparison(Operation.GE, other);
        }

        @Override
        public Predicate<T> gt(N value) {
            return comparison(Operation.GT, value);
        }

        @Override
        public Predicate<T> gt(Expression<T, N> other) {
            return comparison(Operation.GT, other);
        }

        @Override
        public Predicate<T> le(N value) {
            return comparison(Operation.LE, value);
        }

        @Override
        public Predicate<T> le(Expression<T, N> other) {
            return comparison(Operation.LE, other);
        }

        @Override
        public Predicate<T> lt(N value) {
            return comparison(Operation.LT, value);
        }

        @Override
        public Predicate<T> lt(Expression<T, N> other) {
            return comparison(Operation.LT, other);
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

        @Override
        public Predicate<T> between(Expression<T, N> from, Expression<T, N> to) {
            return range(Operation.BETWEEN, from, to);
        }

        @Override
        public Predicate<T> notBetween(N from, N to) {
            return range(Operation.BETWEEN, from, to).not();
        }
    }

    /// 聚合结果值：`subject = this`，因此后端能区分"左侧是 `MAX(x)`"与"左侧是 `x`"。
    ///
    /// @param <T> 根实体类型
    /// @param <U> 聚合结果类型
    abstract static non-sealed class AbstractAggregate<T, U> extends AbstractOperators<T> implements Op {

        private final Operation operation;

        AbstractAggregate(Operation operation, List<PathRef<?, ?>> refs) {
            super(refs, null, null, false);
            this.operation = operation;
        }

        @Override
        public final Operation operation() {
            return operation;
        }

        @Override
        public final List<Object> arguments() {
            return List.of();
        }

        @Override
        public final Op subject() {
            return null;
        }
    }

    static final class AggregateValueImpl<T, U> extends AbstractAggregate<T, U> implements AggregateValue<T, U> {

        AggregateValueImpl(Operation operation, List<PathRef<?, ?>> refs) {
            super(operation, refs);
        }

        @Override
        public Predicate<T> eq(U value) {
            return comparison(Operation.EQ, value, this);
        }

        @Override
        public Predicate<T> eqIfNotNull(U value) {
            return value == null ? alwaysTrue() : comparison(Operation.EQ, value, this);
        }

        @Override
        public Predicate<T> ne(U value) {
            return comparison(Operation.NE, value, this);
        }

        @Override
        public Predicate<T> ge(U value) {
            return comparison(Operation.GE, value, this);
        }

        @Override
        public Predicate<T> gt(U value) {
            return comparison(Operation.GT, value, this);
        }

        @Override
        public Predicate<T> le(U value) {
            return comparison(Operation.LE, value, this);
        }

        @Override
        public Predicate<T> lt(U value) {
            return comparison(Operation.LT, value, this);
        }

        @Override
        public Predicate<T> between(U from, U to) {
            return range(Operation.BETWEEN, from, to, this);
        }

        @Override
        public Predicate<T> between(Expression<T, U> from, Expression<T, U> to) {
            return range(Operation.BETWEEN, from, to, this);
        }

        @Override
        public Predicate<T> notBetween(U from, U to) {
            return range(Operation.BETWEEN, from, to, this).not();
        }
    }

    /// 算术结果值：左操作数由 `subject()` 携带（`null` 表示左操作数就是 `refs()` 的路径）。
    ///
    /// @param <T> 根实体类型
    /// @param <U> 数值类型
    abstract static non-sealed class AbstractArithmetic<T, U extends Number> extends AbstractOperators<T>
            implements Op {

        private final Operation operation;
        private final Object right;
        private final Op leftSubject;

        AbstractArithmetic(Operation operation, List<PathRef<?, ?>> refs, Object right, Op leftSubject) {
            super(refs, null, null, false);
            this.operation = operation;
            this.right = right;
            this.leftSubject = leftSubject;
        }

        @Override
        public final Operation operation() {
            return operation;
        }

        @Override
        public final List<Object> arguments() {
            return Collections.singletonList(right);
        }

        @Override
        public final Op subject() {
            return leftSubject;
        }
    }

    @SuppressWarnings("PMD.TooManyMethods") // 方法数由 NumberValue 接口面决定，不是上帝类
    static final class ArithmeticValueImpl<T, U extends Number> extends AbstractArithmetic<T, U>
            implements NumberValue<T, U> {

        ArithmeticValueImpl(Operation operation, List<PathRef<?, ?>> refs, Object right, Op leftSubject) {
            super(operation, refs, right, leftSubject);
        }

        @Override
        public NumberValue<T, U> add(U addend) {
            return new ArithmeticValueImpl<>(Operation.ADD, refs(), addend, this);
        }

        @Override
        public NumberValue<T, U> sub(U subtrahend) {
            return new ArithmeticValueImpl<>(Operation.SUB, refs(), subtrahend, this);
        }

        @Override
        public NumberValue<T, U> mul(U multiplier) {
            return new ArithmeticValueImpl<>(Operation.MUL, refs(), multiplier, this);
        }

        @Override
        public NumberValue<T, U> div(U divisor) {
            return new ArithmeticValueImpl<>(Operation.DIV, refs(), divisor, this);
        }

        @Override
        public NumberValue<T, U> mod(U divisor) {
            return new ArithmeticValueImpl<>(Operation.MOD, refs(), divisor, this);
        }

        @Override
        public Predicate<T> eq(U value) {
            return comparison(Operation.EQ, value, this);
        }

        @Override
        public Predicate<T> eqIfNotNull(U value) {
            return value == null ? alwaysTrue() : comparison(Operation.EQ, value, this);
        }

        @Override
        public Predicate<T> ne(U value) {
            return comparison(Operation.NE, value, this);
        }

        @Override
        public Predicate<T> ge(U value) {
            return comparison(Operation.GE, value, this);
        }

        @Override
        public Predicate<T> gt(U value) {
            return comparison(Operation.GT, value, this);
        }

        @Override
        public Predicate<T> le(U value) {
            return comparison(Operation.LE, value, this);
        }

        @Override
        public Predicate<T> lt(U value) {
            return comparison(Operation.LT, value, this);
        }

        @Override
        public Predicate<T> between(U from, U to) {
            return range(Operation.BETWEEN, from, to, this);
        }

        @Override
        public Predicate<T> between(Expression<T, U> from, Expression<T, U> to) {
            return range(Operation.BETWEEN, from, to, this);
        }

        @Override
        public Predicate<T> notBetween(U from, U to) {
            return range(Operation.BETWEEN, from, to, this).not();
        }
    }

    /// 构造聚合值。
    ///
    /// @param operation 聚合运算
    /// @param refs      被聚合的路径链
    /// @param <T>        根实体类型
    /// @param <U>        聚合结果类型
    /// @return 聚合值
    static <T, U> AggregateValue<T, U> aggregate(Operation operation, List<PathRef<?, ?>> refs) {
        return new AggregateValueImpl<>(operation, refs);
    }

    /// 构造算术值。
    ///
    /// @param operation   算术运算
    /// @param refs        左操作数的路径链
    /// @param right       右操作数（一个值或另一个表达式）
    /// @param leftSubject 左操作数表达式；`null` 表示左操作数就是 `refs()` 的路径
    /// @param <T>         根实体类型
    /// @param <U>         数值类型
    /// @return 算术值
    static <T, U extends Number> NumberValue<T, U> arithmetic(
            Operation operation, List<PathRef<?, ?>> refs, Object right, Op leftSubject) {
        return new ArithmeticValueImpl<>(operation, refs, right, leftSubject);
    }
}
