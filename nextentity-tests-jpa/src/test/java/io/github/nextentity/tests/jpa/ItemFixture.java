package io.github.nextentity.tests.jpa;

import io.github.nextentity.jpa.exec.JpaPersistExecutor;
import io.github.nextentity.jpa.meta.JpaMeta;
import io.github.nextentity.tests.jpa.support.HibernateHarness;
import jakarta.persistence.EntityManager;
import java.util.List;

/// 一致性用例的 JPA 侧夹具：起库、清空、写入 `ItemEntity`，并暴露同一个 `EntityManager`。
///
/// 清空用 JPQL `delete`：`core.PersistExecutor` 按接缝约定不含删除（point-mall 的删除全走
/// Spring Data `JpaRepository`），所以这里由测试自己清表。
final class ItemFixture implements AutoCloseable {

    private static final String CLEAR_ALL = "delete from ItemEntity";

    private final HibernateHarness harness;
    private final JpaMeta meta;
    private final JpaPersistExecutor<ItemEntity> persist;

    private ItemFixture(HibernateHarness harness) {
        this.harness = harness;
        this.meta = new JpaMeta(harness.entityManagerFactory());
        this.persist = new JpaPersistExecutor<>(harness.entityManager(), meta, ItemEntity.class);
    }

    /// 启动一个只含 `ItemEntity` 的 H2 装置。
    ///
    /// @param databaseName H2 内存库名
    /// @return 夹具
    static ItemFixture start(String databaseName) {
        return new ItemFixture(HibernateHarness.start(databaseName, ItemEntity.class));
    }

    /// 清空后写入给定夹具（一个事务内完成，提交后清一级缓存，保证下一次查询从库里读）。
    ///
    /// @param items 夹具
    void seed(List<ItemEntity> items) {
        harness.inTransaction(entityManager -> {
            entityManager.createQuery(CLEAR_ALL).executeUpdate();
            persist.insertAll(items);
        });
        harness.entityManager().clear();
    }

    /// 与写入共享的实体管理器。
    ///
    /// @return 实体管理器
    EntityManager entityManager() {
        return harness.entityManager();
    }

    @Override
    public void close() {
        harness.close();
    }
}
