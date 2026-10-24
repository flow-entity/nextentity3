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
import io.github.nextentity.api.ProjectionMapper;
import io.github.nextentity.api.SelectStep;
import io.github.nextentity.api.StringOperator;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.api.model.Slice;
import io.github.nextentity.api.model.Tuple2;
import io.github.nextentity.api.model.Tuple3;
import io.github.nextentity.core.compile.Compiler;
import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.core.meta.EntityType;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Constant;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.Logical;
import io.github.nextentity.core.spec.Ordering;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.Selection;
import io.github.nextentity.core.spec.Spec;
import io.github.nextentity.core.spec.ValueSelection;
import io.github.nextentity.core.spec.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// 查询构建器：把 api 的步骤接口翻译成不可变 `QuerySpec`，再把执行交给后端。
///
/// 分层（DESIGN §3）：本类只做"步骤 → 节点"，不拼 SQL/Criteria；编译与执行分别由
/// {@link Compiler} 与 {@link Executor} 负责，因此同一个构建器可以驱动任意后端。
/// 表达式 → 投影节点在 [ValueProjections]，原始行 → 结果对象在 [RowAssembler]。
///
/// 两条终止符语义在 core 落地而不是后端（2.2.3 `Collector` 的 default 方法即如此）：
/// - `first()/single()/slice()` 用 `list` + `count` 组合；
/// - 分页且没有 `orderBy` 时自动追加 `ORDER BY 主键 ASC`（2.2.3 `autoAddIdOrder` 默认开启；去重投影除外）。
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
    private Spec having;
    private LockMode lock;
    private ProjectionMapper<T, ?> mapper;
    private boolean pendingDistinct;

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
        } else if (!OpInterpreter.isPlaceholder(expression)) {
            throw OpInterpreter.notCondition(expression, "where(...)");
        }
        return this;
    }

    @Override
    public <N> PathOperator<T, N, ? extends BaseWhereStep<T, T>> where(PathRef<T, N> ref) {
        return new PathStepOperator<>(PathRefs.resolve(ref), this::addConditionStep, this::selfStep);
    }

    @Override
    public StringOperator<T, ? extends BaseWhereStep<T, T>> where(PathRef.StringRef<T> ref) {
        return new StringStepOperator<>(PathRefs.resolve(ref), this::addConditionStep, this::selfStep);
    }

    @Override
    public <N> PathOperator<T, N, ? extends BaseWhereStep<T, T>> where(Path<T, N> path) {
        return new PathStepOperator<>(PathRefs.resolve(path.refs()), this::addConditionStep, this::selfStep);
    }

    @Override
    public StringOperator<T, ? extends BaseWhereStep<T, T>> where(StringPath<T> path) {
        return new StringStepOperator<>(PathRefs.resolve(path.refs()), this::addConditionStep, this::selfStep);
    }

    // ------------------------------------------------------------- distinct

    @Override
    public SelectStep<T> distinct() {
        pendingDistinct = true;
        return this;
    }

    // --------------------------------------------------------------- select

    @Override
    public <R> BaseWhereStep<T, R> select(PathRef<T, R> ref) {
        return applySelection(new ValueSelection(List.of(ValueProjections.of(ref)), takeDistinct()), null);
    }

    @Override
    public <R> BaseWhereStep<T, R> select(Expression<T, R> expression) {
        return applySelection(new ValueSelection(List.of(ValueProjections.of(expression)), takeDistinct()), null);
    }

    @Override
    public <A, B> BaseWhereStep<T, Tuple2<A, B>> select(Expression<T, A> first, Expression<T, B> second) {
        return applySelection(
                new ValueSelection(List.of(ValueProjections.of(first), ValueProjections.of(second)), takeDistinct()),
                null);
    }

    @Override
    public <A, B, C> BaseWhereStep<T, Tuple3<A, B, C>> select(
            Expression<T, A> first, Expression<T, B> second, Expression<T, C> third) {
        return applySelection(
                new ValueSelection(
                        List.of(ValueProjections.of(first), ValueProjections.of(second), ValueProjections.of(third)),
                        takeDistinct()),
                null);
    }

    @Override
    public <R> BaseWhereStep<T, R> project(ProjectionMapper<T, R> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        // expressions() 只求值一次：求值两次时「校验用的列集」与「建投影用的列集」会分叉
        return applySelection(new ValueSelection(ValueProjections.ofAll(mapper.expressions()), takeDistinct()), mapper);
    }

    // -------------------------------------------------------------- orderBy

    @Override
    public OrderOperator<T, T> orderBy(PathRef<T, ? extends Comparable<?>> ref) {
        return orderBy(PathRefs.resolve(ref));
    }

    @Override
    public OrderOperator<T, T> orderBy(Path<T, ? extends Comparable<?>> path) {
        return orderBy(PathRefs.resolve(path.refs()));
    }

    // ----------------------------------------------------- groupBy / having

    @Override
    public BaseWhereStep<T, T> groupBy(PathRef<T, ?> ref) {
        groupBy.add(PathRefs.resolve(ref));
        return this;
    }

    @Override
    @SafeVarargs
    public final BaseWhereStep<T, T> groupBy(PathRef<T, ?>... refs) {
        for (PathRef<T, ?> ref : refs) {
            groupBy.add(PathRefs.resolve(ref));
        }
        return this;
    }

    @Override
    public BaseWhereStep<T, T> having(Expression<T, Boolean> expression) {
        if (expression instanceof Op op) {
            having = mergeCondition(having, OpInterpreter.toSpec(op));
        } else if (!OpInterpreter.isPlaceholder(expression)) {
            throw OpInterpreter.notCondition(expression, "having(...)");
        }
        return this;
    }

    @Override
    public Collector<T> lock(LockMode mode) {
        lock = Objects.requireNonNull(mode, "mode");
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
        // 窗口必须在 count() 之前构造：`offset >= total` 的短路会跳过列表查询，校验若留在后面，
        // `slice(4, -1)` 就静默返回空页，而契约（`SliceStep#slice`）无条件承诺 IAE。
        Window window = new Window(offset, limit);
        long total = count();
        if (total <= offset) {
            return new SliceResult<>(List.of(), total);
        }
        return new SliceResult<>(fetch(window), total);
    }

    // -------------------------------------------------------------- internal

    /// 换投影：`selection` 与 `mapper` 必须同时换。
    ///
    /// 映射器路径的结果行按 `mapper` 装配，`select(...)` 路径按 `selection` 装配；两者一旦不同步，
    /// `map()` 收到的 `Tuple` 列数就与它自己的 `expressions()` 不符。
    private <U> BaseWhereStep<T, U> applySelection(Selection selection, ProjectionMapper<T, ?> mapper) {
        this.selection = selection;
        this.mapper = mapper;
        return step();
    }

    @SuppressWarnings("unchecked")
    private <U> BaseWhereStep<T, U> step() {
        return (BaseWhereStep<T, U>) this;
    }

    private BaseWhereStep<T, T> selfStep() {
        return this;
    }

    /// 取走「下一次投影是否去重」的旗标：只生效一次，投影之后回到不去重。
    private boolean takeDistinct() {
        boolean distinct = pendingDistinct;
        pendingDistinct = false;
        return distinct;
    }

    private boolean distinctSelection() {
        return selection instanceof ValueSelection value && value.distinct();
    }

    private BaseWhereStep<T, T> addConditionStep(Spec spec) {
        addCondition(spec);
        return this;
    }

    private BaseWhereStep<T, T> ordered(AttributePath path, Ordering.Direction direction) {
        orderBy.add(new Ordering(path, direction));
        return this;
    }

    private OrderOperator<T, T> orderBy(AttributePath path) {
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

    /// 追加一个 WHERE 条件：`Constant.TRUE`（`*IfNotNull` 短路产生）不进入 WHERE，
    /// 其余条件与已有条件做 `AND`（2.2.3 `WhereImpl.andWhere`）。
    private void addCondition(Spec spec) {
        where = mergeCondition(where, spec);
    }

    /// 与已有条件做 `AND`；`Constant.TRUE` 视为"没有条件"。
    private static Spec mergeCondition(Spec current, Spec spec) {
        if (spec == Constant.TRUE) {
            return current;
        }
        return current == null ? spec : Logical.and(current, spec);
    }

    @SuppressWarnings("unchecked")
    private List<T> fetch(Window window) {
        List<Object> rows = executor.list(compiler.compile(spec(window)));
        return (List<T>) RowAssembler.assemble(rows, selection, mapper);
    }

    /// 组装当前状态下的查询规格。
    ///
    /// 带 limit 的分页且没有排序、不是聚合查询、也不是去重投影时，追加 `ORDER BY 主键 ASC`——
    /// 2.2.3 `autoAddIdOrder` 默认 true 且 point-mall 未覆盖，去掉它会改变 `first()`/`slice()`
    /// 取到哪一行。
    ///
    /// **去重投影下不追加**：主键通常不在 `SELECT DISTINCT` 的投影列里，MySQL 会拒绝
    /// `SELECT DISTINCT x ... ORDER BY 主键`（H2 放行，只会在真方言炸）。稳定分页请显式 `orderBy`。
    private QuerySpec spec(Window window) {
        QuerySpec spec = new QuerySpec(entityType.type(), selection, where, groupBy, having, orderBy, window, lock);
        if (!window.limited() || !orderBy.isEmpty() || spec.aggregate() || distinctSelection()) {
            return spec;
        }
        List<Ordering> orders = new ArrayList<>(orderBy);
        orders.add(
                new Ordering(AttributePath.of(entityType.type(), entityType.id().name()), Ordering.Direction.ASC));
        return new QuerySpec(entityType.type(), selection, where, groupBy, having, orders, window, lock);
    }
}
