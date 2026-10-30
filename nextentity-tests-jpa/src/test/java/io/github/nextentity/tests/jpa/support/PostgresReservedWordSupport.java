package io.github.nextentity.tests.jpa.support;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.core.EntityOperationsFactory;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.JpaEntityOperationsFactory;
import io.github.nextentity.testing.ReservedWordSupport;
import io.github.nextentity.tests.jpa.ReservedWordEntity;
import jakarta.persistence.EntityManager;
import java.util.List;

/// 保留字实体的 PostgreSQL 容器接线：连共享容器、hbm2ddl 建引用表、清表写夹具。
///
/// 与 H2 侧 `JpaReservedWordSupport` 只差「连哪个库」；引用渲染由 Hibernate PostgreSQL 方言负责。
public final class PostgresReservedWordSupport implements ReservedWordSupport<ReservedWordEntity>, AutoCloseable {

    private static final String CLEAR_ALL = "delete from ReservedWordEntity";

    private final HibernateHarness harness;
    private final EntityOperations<ReservedWordEntity> operations;

    public PostgresReservedWordSupport() {
        this.harness = HibernateHarness.start(
                PostgresL4Container.jdbcUrl(),
                PostgresL4Container.username(),
                PostgresL4Container.password(),
                ReservedWordEntity.class);
        EntityOperationsFactory factory =
                new JpaEntityOperationsFactory(harness.entityManager(), harness.entityManagerFactory());
        this.operations = factory.operations(ReservedWordEntity.class);
    }

    @Override
    public EntityQuery<ReservedWordEntity> query() {
        return operations.query();
    }

    @Override
    public void seedData(List<ReservedWordEntity> rows) {
        // 共享装置里持久化上下文可能留着上一个用例的受管实体：先清掉，避免同主键被判成已有关联对象。
        EntityManager entityManager = harness.entityManager();
        entityManager.clear();
        entityManager.getTransaction().begin();
        try {
            entityManager.createQuery(CLEAR_ALL).executeUpdate();
            operations.insertAll(rows);
            entityManager.getTransaction().commit();
        } catch (RuntimeException e) {
            if (entityManager.getTransaction().isActive()) {
                entityManager.getTransaction().rollback();
            }
            throw e;
        } finally {
            entityManager.clear();
        }
    }

    @Override
    public ReservedWordEntity newReservedWord(long id, String name, String desc, Integer key) {
        return new ReservedWordEntity(id, name, desc, key);
    }

    @Override
    public void insert(ReservedWordEntity row) {
        write(() -> operations.insert(row));
    }

    @Override
    public void insertAll(Iterable<ReservedWordEntity> rows) {
        write(() -> operations.insertAll(rows));
    }

    @Override
    public void update(ReservedWordEntity row) {
        write(() -> operations.update(row));
    }

    /// 写入必须落在事务里（共享装置只有一个 EM，失败要回滚，否则后续用例全报事务已激活）。
    private void write(Runnable action) {
        EntityManager entityManager = harness.entityManager();
        entityManager.getTransaction().begin();
        try {
            action.run();
            entityManager.flush();
            entityManager.getTransaction().commit();
        } catch (RuntimeException e) {
            if (entityManager.getTransaction().isActive()) {
                entityManager.getTransaction().rollback();
            }
            throw e;
        } finally {
            entityManager.clear();
        }
    }

    @Override
    public PathRef<ReservedWordEntity, Long> idRef() {
        return ReservedWordEntity::id;
    }

    @Override
    public PathRef.StringRef<ReservedWordEntity> nameRef() {
        return ReservedWordEntity::name;
    }

    @Override
    public PathRef.StringRef<ReservedWordEntity> descRef() {
        return ReservedWordEntity::desc;
    }

    @Override
    public PathRef.NumberRef<ReservedWordEntity, Integer> keyRef() {
        return ReservedWordEntity::key;
    }

    @Override
    public void close() {
        harness.close();
    }
}
