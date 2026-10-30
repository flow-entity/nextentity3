package io.github.nextentity.core.builder;

import io.github.nextentity.api.Expression;
import io.github.nextentity.api.Op;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.PathOperator;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.api.StringOperator;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.api.Whereable;
import io.github.nextentity.core.spec.Spec;

/// 写语句的条件阶段：`update()` / `delete()` 的 `where(...)` 与查询**共用同一套算子**。
///
/// 只把「条件如何累加到同一条语句上」放在这里，语句形态各自由子类负责；
/// 这样 `eq` / `in` / `between` / `startsWith` / `*IfNotNull` 的语义不会在写语句里另写一份。
///
/// @param <T>    实体类型
/// @param <SELF> 继续链式时返回的步骤类型
abstract class WhereStepBuilder<T, SELF extends Whereable<T, SELF>> implements Whereable<T, SELF> {

    private Spec where;

    /// 该语句当前累积的 WHERE：未追加条件时为 null（无条件）。
    protected final Spec where() {
        return where;
    }

    /// 本构建器在 `where` 之后要继续返回的步骤。
    protected abstract SELF self();

    @Override
    public SELF where(Expression<T, Boolean> expression) {
        if (expression instanceof Op op) {
            addCondition(OpInterpreter.toSpec(op));
        } else if (!OpInterpreter.isPlaceholder(expression)) {
            throw OpInterpreter.notCondition(expression, "where(...)");
        }
        return self();
    }

    @Override
    public <N> PathOperator<T, N, ? extends SELF> where(PathRef<T, N> ref) {
        return new PathStepOperator<>(PathRefs.resolve(ref), this::conditionStep, this::self);
    }

    @Override
    public StringOperator<T, ? extends SELF> where(PathRef.StringRef<T> ref) {
        return new StringStepOperator<>(PathRefs.resolve(ref), this::conditionStep, this::self);
    }

    @Override
    public <N> PathOperator<T, N, ? extends SELF> where(Path<T, N> path) {
        return new PathStepOperator<>(PathRefs.resolve(path.refs()), this::conditionStep, this::self);
    }

    @Override
    public StringOperator<T, ? extends SELF> where(StringPath<T> path) {
        return new StringStepOperator<>(PathRefs.resolve(path.refs()), this::conditionStep, this::self);
    }

    /// 追加一个条件：与其他条件做 `AND`，`Constant.TRUE`（`*IfNotNull` 短路）不进入条件。
    private void addCondition(Spec spec) {
        where = QueryBuilder.mergeCondition(where, spec);
    }

    private SELF conditionStep(Spec spec) {
        addCondition(spec);
        return self();
    }
}
