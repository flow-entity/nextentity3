package io.github.nextentity.tests.jpa;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.core.EntityOperationsFactory;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.JpaEntityOperationsFactory;
import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.ItemStatus;
import io.github.nextentity.tests.jpa.support.HibernateHarness;
import java.util.List;
import java.util.function.Supplier;

/// 一致性用例的 JPA 接线：起 H2、用生产同款工厂取操作入口、清表并写夹具。
///
/// 装配走 `JpaEntityOperationsFactory`（`NextEntityAutoConfiguration` 里注册的就是它），
/// 因此一致性用例验证的是生产路径，而不是测试专用旁路。
final class JpaItemSupport implements ConsistencySupport<ItemEntity>, AutoCloseable {

    private static final String CLEAR_ALL = "delete from ItemEntity";

    private final HibernateHarness harness;
    private final EntityOperations<ItemEntity> operations;

    JpaItemSupport(String databaseName) {
        this.harness = HibernateHarness.start(databaseName, ItemEntity.class);
        EntityOperationsFactory factory =
                new JpaEntityOperationsFactory(harness.entityManager(), harness.entityManagerFactory());
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
    public ItemEntity newItem(long id, String name, Integer score, String note, ItemStatus status) {
        return new ItemEntity(id, name, score, note, status);
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
    public PathRef<ItemEntity, ItemStatus> statusRef() {
        return ItemEntity::status;
    }

    /// 在事务里执行并返回结果（悲观锁等要求事务的查询用得到）。
    ///
    /// @param work 事务体
    /// @param <R>  结果类型
    /// @return 事务体返回值
    <R> R inTransaction(Supplier<R> work) {
        return harness.inTransaction(work);
    }

    /// 取走已抓到的 SQL。
    ///
    /// @return SQL 列表
    List<String> drainSql() {
        return harness.drainSql();
    }

    /// 清空 SQL 账本。
    void clearSql() {
        harness.clearSql();
    }

    @Override
    public void close() {
        harness.close();
    }
}
