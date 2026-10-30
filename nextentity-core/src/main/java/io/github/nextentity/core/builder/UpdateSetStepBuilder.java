package io.github.nextentity.core.builder;

import io.github.nextentity.api.Path;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.api.UpdateSetStep;
import io.github.nextentity.api.UpdateWhereStep;
import io.github.nextentity.core.spec.Assignment;
import io.github.nextentity.core.spec.UpdateSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/// 条件更新的构建器：收 `set(...)` 与 `where(...)`，[#execute()] 交给后端渲染并执行。
///
/// 本类只做"收集"，不含任何后端类型：后端拿到 [UpdateSpec] 后自己生成语句，
/// 因此 JPA / JDBC 两个后端不需要各自再写一遍 `set` 顺序、条件合并这些语义。
///
/// **状态是可变的**（一条语句构建完就执行），因此不要跨线程共享同一个构建器。
///
/// @param <T> 实体类型
public final class UpdateSetStepBuilder<T> extends WhereStepBuilder<T, UpdateWhereStep<T>> implements UpdateSetStep<T> {

    private final Class<T> entityType;
    private final Function<UpdateSpec, Integer> execution;
    private final List<Assignment> assignments = new ArrayList<>();

    /// 创建条件更新构建器。
    ///
    /// @param entityType 更新的实体类型，非 null
    /// @param execution  执行器：把规格渲染成语句、执行、返回受影响行数，非 null
    public UpdateSetStepBuilder(Class<T> entityType, Function<UpdateSpec, Integer> execution) {
        this.entityType = Objects.requireNonNull(entityType, "entityType");
        this.execution = Objects.requireNonNull(execution, "execution");
    }

    @Override
    public <U> UpdateSetStep<T> set(PathRef<T, U> path, U value) {
        assignments.add(new Assignment(PathRefs.resolve(path), value));
        return this;
    }

    @Override
    public <U> UpdateSetStep<T> set(Path<T, U> path, U value) {
        assignments.add(new Assignment(PathRefs.resolve(path.refs()), value));
        return this;
    }

    @Override
    public int execute() {
        return execution.apply(new UpdateSpec(entityType, assignments, where()));
    }

    @Override
    protected UpdateWhereStep<T> self() {
        return this;
    }
}
