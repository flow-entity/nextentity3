package io.github.nextentity.core.builder;

import io.github.nextentity.api.DeleteWhereStep;
import io.github.nextentity.core.spec.DeleteSpec;
import java.util.Objects;
import java.util.function.Function;

/// 条件删除的构建器：收 `where(...)`，[#execute()] 交给后端渲染并执行。
///
/// 本类只做"收集"，不含任何后端类型：后端拿到 [DeleteSpec] 后自己生成语句，
/// 因此 JPA / JDBC 两个后端不需要各自再写一遍条件合并语义。
///
/// **状态是可变的**（一条语句构建完就执行），因此不要跨线程共享同一个构建器。
///
/// @param <T> 实体类型
public final class DeleteWhereStepBuilder<T> extends WhereStepBuilder<T, DeleteWhereStep<T>>
        implements DeleteWhereStep<T> {

    private final Class<T> entityType;
    private final Function<DeleteSpec, Integer> execution;

    /// 创建条件删除构建器。
    ///
    /// @param entityType 删除的实体类型，非 null
    /// @param execution  执行器：把规格渲染成语句、执行、返回受影响行数，非 null
    public DeleteWhereStepBuilder(Class<T> entityType, Function<DeleteSpec, Integer> execution) {
        this.entityType = Objects.requireNonNull(entityType, "entityType");
        this.execution = Objects.requireNonNull(execution, "execution");
    }

    @Override
    public int execute() {
        return execution.apply(new DeleteSpec(entityType, where()));
    }

    @Override
    protected DeleteWhereStep<T> self() {
        return this;
    }
}
