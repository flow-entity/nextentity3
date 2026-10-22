package io.github.nextentity.jpa;

import io.github.nextentity.core.EntityOperationsFactory;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.core.repo.SimpleEntityOperationsFactory;
import io.github.nextentity.jpa.compile.JpaCompiler;
import io.github.nextentity.jpa.compile.JpaQuery;
import io.github.nextentity.jpa.exec.JpaExecutor;
import io.github.nextentity.jpa.exec.JpaPersistExecutor;
import io.github.nextentity.jpa.meta.JpaMeta;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

/// JPA 后端的 `EntityOperationsFactory`：只把四个零件交给 core 的装配器，装配逻辑不在这里重写。
///
/// `JpaMeta` 由 `EntityManagerFactory` 建一次（元模型是应用级的），`JpaCompiler` / `JpaExecutor` /
/// `JpaPersistExecutor` 用调用方注入的 `EntityManager`（Spring 里就是事务绑定的那个代理）。
public final class JpaEntityOperationsFactory implements EntityOperationsFactory {

    private final SimpleEntityOperationsFactory<JpaQuery> delegate;

    /// 创建工厂。
    ///
    /// @param entityManager        实体管理器（事务绑定的那个）
    /// @param entityManagerFactory 实体管理器工厂，用于解析元模型
    public JpaEntityOperationsFactory(EntityManager entityManager, EntityManagerFactory entityManagerFactory) {
        JpaMeta meta = new JpaMeta(entityManagerFactory);
        this.delegate = new SimpleEntityOperationsFactory<>(
                meta::entityType,
                new JpaCompiler(entityManager, meta),
                new JpaExecutor(entityManager, meta),
                type -> new JpaPersistExecutor<>(entityManager, meta, type));
    }

    @Override
    public <T> EntityOperations<T> operations(Class<T> entityClass) {
        return delegate.operations(entityClass);
    }
}
