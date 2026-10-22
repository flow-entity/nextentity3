package io.github.nextentity.core.builder;

import io.github.nextentity.api.BaseWhereStep;
import io.github.nextentity.api.Collector;
import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.Expression;
import io.github.nextentity.api.LockMode;
import io.github.nextentity.api.Op;
import io.github.nextentity.api.OrderOperator;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.PathOperator;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.api.StringOperator;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.api.model.Slice;
import io.github.nextentity.api.model.Tuple2;
import io.github.nextentity.core.compile.Compiler;
import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.core.meta.EntityType;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Constant;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.Logical;
import io.github.nextentity.core.spec.Ordering;
import io.github.nextentity.core.spec.PathProjection;
import io.github.nextentity.core.spec.Projection;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.Selection;
import io.github.nextentity.core.spec.Spec;
import io.github.nextentity.core.spec.ValueSelection;
import io.github.nextentity.core.spec.Window;
import java.util.ArrayList;
import java.util.List;

/// 查询构建器：把 api 的步骤接口翻译成不可变 `QuerySpec`，再把执行交给后端。
///
/// 分层（DESIGN §3）：本类只做"步骤 → 节点"，不拼 SQL/Criteria；编译与执行分别由
/// {@link Compiler} 与 {@link Executor} 负责，因此同一个构建器可以驱动任意后端。
///
/// 两条终止符语义在 core 落地而不是后端（2.2.3 `Collector` 的 default 方法即如此）：
/// - `first()/single()/slice()` 用 `list` + `count` 组合；
/// - 分页且没有 `orderBy` 时自动追加 `ORDER BY 主键 ASC`（2.2.3 `autoAddIdOrder` 默认开启）。
///
/// @param <T> 实体类型
/// @param <S> 后端语句类型
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CouplingBetweenObjects"}) // 方法数由 api 步骤接口面决定；耦合的 api 类型正是它要实现的那套 DSL
public final class QueryBuilder<T, S> implements EntityQuery<T> {

    private final EntityType<T> entityType;
    private final Compiler<S> compiler;
    private final Executor<S> executor;

    private final List<AttributePath> groupBy = new ArrayList<>();
    private final List<Ordering> orderBy = new ArrayList<>();
    private Selection selection = new EntitySelection();
    private Spec where;
    private LockMode lock;

    /// 创建查询构建器。
    ///
    /// @param entityType 实体元数据（主键用于分页自动排序）
    /// @param compiler   编译器
    /// @param executor   执行器
    public QueryBuilder(EntityType<T> entityType, Compiler<S> compiler, Executor<S> executor) {
        this.entityType = entityType;
        this.compiler = compiler;
        this.executor = executor;
    }

    // ---------------------------------------------------------------- where

    @Override
    public BaseWhereStep<T, T> where(Expression<T, Boolean> expression) {
        if (expression instanceof Op op) {
            addCondition(OpInterpreter.toSpec(op));
        }
        return this;
    }

    @Override
    public <N> PathOperator<T, N, ? extends BaseWhereStep<T, T>> where(PathRef<T, N> ref) {
        return new PathStepOperator<>(PathRefs.resolve(List.of(ref)), this::addConditionStep, this::selfStep);
    }

    @Override
    public StringOperator<T, ? extends BaseWhereStep<T, T>> where(PathRef.StringRef<T> ref) {
        return new StringStepOperator<>(PathRefs.resolve(List.of(ref)), this::addConditionStep, this::selfStep);
    }

    @Override
    public StringOperator<T, ? extends BaseWhereStep<T, T>> where(StringPath<T> path) {
        return new StringStepOperator<>(PathRefs.resolve(path.refs()), this::addConditionStep, this::selfStep);
    }

    // --------------------------------------------------------------- select

    @Override
    public <R> BaseWhereStep<T, R> select(PathRef<T, R> ref) {
        selection = new ValueSelection(List.of(new PathProjection(PathRefs.resolve(List.of(ref)))), false);
        return step();
    }

    @Override
    public <R> BaseWhereStep<T, R> select(Expression<T, R> expression) {
        selection = new ValueSelection(List.of(projection(expression)), false);
        return step();
    }

    @Override
    public <A, B> BaseWhereStep<T, Tuple2<A, B>> select(Expression<T, A> first, Expression<T, B> second) {
        selection = new ValueSelection(List.of(projection(first), projection(second)), false);
        return step();
    }

    @Override
    public <R> BaseWhereStep<T, R> selectDistinct(PathRef<T, R> ref) {
        selection = new ValueSelection(List.of(new PathProjection(PathRefs.resolve(List.of(ref)))), true);
        return step();
    }

    // -------------------------------------------------------------- orderBy

    @Override
    public OrderOperator<T, T> orderBy(PathRef<T, ? extends Comparable<?>> ref) {
        AttributePath path = PathRefs.resolve(List.of(ref));
        return new OrderOperator<>() {
            @Override
            public BaseWhereStep<T, T> asc() {
                return ordered(path, Ordering.Direction.ASC);
            }

            @Override
            public BaseWhereStep<T, T> desc() {
                return ordered(path, Ordering.Direction.DESC);
            }
        };
    }

    @Override
    public BaseWhereStep<T, T> groupBy(PathRef<T, ?> ref) {
        groupBy.add(PathRefs.resolve(List.of(ref)));
        return this;
    }

    @Override
    public Collector<T> lock(LockMode mode) {
        lock = mode;
        return this;
    }

    // ------------------------------------------------------------ terminate

    @Override
    public List<T> list() {
        return fetch(Window.NONE);
    }

    @Override
    public List<T> list(int limit) {
        return fetch(new Window(0, limit));
    }

    @Override
    public T first() {
        List<T> rows = fetch(new Window(0, 1));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public T single() {
        List<T> rows = fetch(new Window(0, 2));
        if (rows.size() > 1) {
            throw new IllegalStateException("found more than one result, expected one");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public long count() {
        return executor.count(compiler.compile(spec(Window.NONE).forCount()));
    }

    @Override
    public boolean exists() {
        return executor.exists(compiler.compile(spec(Window.NONE).forExists()));
    }

    @Override
    public Slice<T> slice(int offset, int limit) {
        long total = count();
        if (total <= offset) {
            return new SliceResult<>(List.of(), total);
        }
        return new SliceResult<>(fetch(new Window(offset, limit)), total);
    }

    // -------------------------------------------------------------- internal

    @SuppressWarnings("unchecked")
    private <U> BaseWhereStep<T, U> step() {
        return (BaseWhereStep<T, U>) this;
    }

    private BaseWhereStep<T, T> selfStep() {
        return this;
    }

    private BaseWhereStep<T, T> addConditionStep(Spec spec) {
        addCondition(spec);
        return this;
    }

    private BaseWhereStep<T, T> ordered(AttributePath path, Ordering.Direction direction) {
        orderBy.add(new Ordering(path, direction));
        return this;
    }

    /// 追加一个条件：`Constant.TRUE`（`*IfNotNull` 短路产生）不进入 WHERE，
    /// 其余条件与已有条件做 `AND`（2.2.3 `WhereImpl.andWhere`）。
    private void addCondition(Spec spec) {
        if (spec == Constant.TRUE) {
            return;
        }
        where = where == null ? spec : Logical.and(where, spec);
    }

    private Projection projection(Expression<?, ?> expression) {
        if (expression instanceof Path<?, ?> path) {
            return new PathProjection(PathRefs.resolve(path.refs()));
        }
        return OpInterpreter.toProjection((Op) expression);
    }

    @SuppressWarnings("unchecked")
    private List<T> fetch(Window window) {
        List<Object> rows = executor.list(compiler.compile(spec(window)));
        if (!(selection instanceof ValueSelection values) || values.items().size() == 1) {
            return (List<T>) rows;
        }
        List<T> assembled = new ArrayList<>(rows.size());
        for (Object row : rows) {
            Object[] columns = (Object[]) row;
            assembled.add((T) new Tuple2Value<>(columns[0], columns[1]));
        }
        return assembled;
    }

    /// 组装当前状态下的查询规格。
    ///
    /// 带 limit 的分页且没有排序、又不是聚合查询时，追加 `ORDER BY 主键 ASC`——
    /// 2.2.3 `autoAddIdOrder` 默认 true 且 point-mall 未覆盖，去掉它会改变 `first()`/`slice()`
    /// 取到哪一行。
    private QuerySpec spec(Window window) {
        QuerySpec spec = new QuerySpec(entityType.type(), selection, where, groupBy, orderBy, window, lock);
        if (!window.limited() || !orderBy.isEmpty() || spec.aggregate()) {
            return spec;
        }
        List<Ordering> orders = new ArrayList<>(orderBy);
        orders.add(
                new Ordering(AttributePath.of(entityType.type(), entityType.id().name()), Ordering.Direction.ASC));
        return new QuerySpec(entityType.type(), selection, where, groupBy, orders, window, lock);
    }
}
