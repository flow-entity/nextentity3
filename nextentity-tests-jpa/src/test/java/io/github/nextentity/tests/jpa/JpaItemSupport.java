package io.github.nextentity.tests.jpa;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.core.EntityOperationsFactory;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.core.repo.SimpleEntityOperationsFactory;
import io.github.nextentity.jpa.compile.JpaCompiler;
import io.github.nextentity.jpa.compile.JpaQuery;
import io.github.nextentity.jpa.exec.JpaExecutor;
import io.github.nextentity.jpa.exec.JpaPersistExecutor;
import io.github.nextentity.jpa.meta.JpaMeta;
import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.tests.jpa.support.HibernateHarness;
import jakarta.persistence.EntityManager;
import java.util.List;

/// 一致性用例的 JPA 接线：起 H2、按生产同款方式装出 `EntityOperations`、清表并写夹具。
///
/// 装配走 core 的 `SimpleEntityOperationsFactory`（与 `NextEntityAutoConfiguration` 同一份装配逻辑），
/// 这样一致性用例验证的正是生产路径的实现，而不是测试专用的旁路。
final class JpaItemSupport implements ConsistencySupport<ItemEntity>, AutoCloseable {

    private static final String CLEAR_ALL = "delete from ItemEntity";

    private final HibernateHarness harness;
    private final EntityOperations<ItemEntity> operations;

    JpaItemSupport(String databaseName) {
        this.harness = HibernateHarness.start(databaseName, ItemEntity.class);
        JpaMeta meta = new JpaMeta(harness.entityManagerFactory());
        EntityManager entityManager = harness.entityManager();
        EntityOperationsFactory factory = new SimpleEntityOperationsFactory<JpaQuery>(
                meta::entityType,
                new JpaCompiler(entityManager, meta),
                new JpaExecutor(entityManager, meta),
                entityType -> new JpaPersistExecutor<>(entityManager, meta, entityType));
        this.operations = factory.operations(ItemEntity.class);
    }

    @Override
    public EntityQuery<ItemEntity> query() {
        return operations.query();
    }

    @Override
    public void seedData(List<ItemEntity> items) {
        harness.inTransaction(entityManager -> {
            entityManager.createQuery(CLEAR_ALL).executeUpdate();
            operations.insertAll(items);
        });
        harness.entityManager().clear();
    }

    @Override
    public ItemEntity newItem(long id, String name, Integer score, String note) {
        return new ItemEntity(id, name, score, note);
    }

    @Override
    public PathRef<ItemEntity, Long> idRef() {
        return ItemEntity::id;
    }

    @Override
    public PathRef.StringRef<ItemEntity> nameRef() {
        return ItemEntity::name;
    }

    @Override
    public PathRef.NumberRef<ItemEntity, Integer> scoreRef() {
        return ItemEntity::score;
    }

    @Override
    public PathRef.StringRef<ItemEntity> noteRef() {
        return ItemEntity::note;
    }

    @Override
    public void close() {
        harness.close();
    }
}
