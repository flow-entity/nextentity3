package io.github.nextentity.tests.jpa;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.core.EntityOperationsFactory;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.JpaEntityOperationsFactory;
import io.github.nextentity.testing.ReservedWordSupport;
import io.github.nextentity.tests.jpa.support.HibernateHarness;
import java.util.List;

/// 保留字实体的 JPA 接线：H2 起库、生产同款工厂取操作入口、清表写夹具、四个属性引用。
///
/// 与 `JpaItemSupport` 同形；`@Table(name="`order`")` 的引用由 Hibernate 方言渲染，
/// 建表走 `hbm2ddl`，所以接线里没有手写 DDL。
final class JpaReservedWordSupport implements ReservedWordSupport<ReservedWordEntity>, AutoCloseable {

    private static final String CLEAR_ALL = "delete from ReservedWordEntity";

    private final HibernateHarness harness;
    private final EntityOperations<ReservedWordEntity> operations;

    JpaReservedWordSupport(String databaseName) {
        this.harness = HibernateHarness.start(databaseName, ReservedWordEntity.class);
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
        harness.inTransaction(entityManager -> {
            entityManager.createQuery(CLEAR_ALL).executeUpdate();
            operations.insertAll(rows);
        });
        harness.entityManager().clear();
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

    /// 写入必须落在事务里：`persist`/`merge` 与 update 语句都要求活动事务，且用例随后要读到结果。
    private void write(Runnable action) {
        harness.inTransaction(entityManager -> {
            action.run();
            entityManager.flush();
        });
        harness.entityManager().clear();
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
