package io.github.nextentity.core.repo;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.core.EntityOperationsFactory;
import io.github.nextentity.core.builder.QueryBuilder;
import io.github.nextentity.core.compile.Compiler;
import io.github.nextentity.core.exec.Executor;
import io.github.nextentity.core.exec.PersistExecutor;
import io.github.nextentity.core.meta.EntityType;
import java.util.function.Function;

/// 装配器：把后端提供的四个零件组装成 `EntityOperationsFactory`。
///
/// 装配逻辑只有这一份——`spring` 只注册一个 bean，`jpa` 只提供零件，
/// 将来第二后端（DESIGN §6）换掉这四个入参即可，`first/single/slice/count/exists`
/// 与分页自动排序这些语义不必重写。
///
/// @param <S> 后端语句类型
public final class SimpleEntityOperationsFactory<S> implements EntityOperationsFactory {

    private final Function<Class<?>, EntityType<?>> meta;
    private final Compiler<S> compiler;
    private final Executor<S> executor;
    private final Function<Class<?>, PersistExecutor<?>> persist;

    /// 创建装配器。
    ///
    /// @param meta     实体类型 → 元数据（由后端的元模型解析）
    /// @param compiler 编译器，非 `null`
    /// @param executor 执行器，非 `null`
    /// @param persist  实体类型 → 写入执行器
    public SimpleEntityOperationsFactory(
            Function<Class<?>, EntityType<?>> meta,
            Compiler<S> compiler,
            Executor<S> executor,
            Function<Class<?>, PersistExecutor<?>> persist) {
        this.meta = meta;
        this.compiler = compiler;
        this.executor = executor;
        this.persist = persist;
    }

    @Override
    public <T> EntityOperations<T> operations(Class<T> entityClass) {
        return new Operations<>(meta(entityClass), compiler, executor, persist(entityClass));
    }

    @SuppressWarnings("unchecked")
    private <T> EntityType<T> meta(Class<T> entityClass) {
        return (EntityType<T>) meta.apply(entityClass);
    }

    @SuppressWarnings("unchecked")
    private <T> PersistExecutor<T> persist(Class<T> entityClass) {
        return (PersistExecutor<T>) persist.apply(entityClass);
    }

    /// 单个实体的操作入口实现：查询交给构建器，写入直接转给后端的写入执行器。
    ///
    /// @param <T> 实体类型
    /// @param <S> 后端语句类型
    private static final class Operations<T, S> implements EntityOperations<T> {

        private final EntityType<T> entityType;
        private final Compiler<S> compiler;
        private final Executor<S> executor;
        private final PersistExecutor<T> persist;

        Operations(EntityType<T> entityType, Compiler<S> compiler, Executor<S> executor, PersistExecutor<T> persist) {
            this.entityType = entityType;
            this.compiler = compiler;
            this.executor = executor;
            this.persist = persist;
        }

        @Override
        public EntityQuery<T> query() {
            return new QueryBuilder<>(entityType, compiler, executor);
        }

        @Override
        public EntityType<T> entityType() {
            return entityType;
        }

        @Override
        public void insert(T entity) {
            persist.insert(entity);
        }

        @Override
        public void insertAll(Iterable<T> entities) {
            persist.insertAll(entities);
        }

        @Override
        public void update(T entity) {
            persist.update(entity);
        }

        @Override
        public void updateAll(Iterable<T> entities) {
            persist.updateAll(entities);
        }
    }
}
